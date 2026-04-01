package mse.controller;

import com.google.gson.JsonObject;
import mse.Graph;
import mse.Node;
import mse.distress.DistressHandler;
import mse.distress.DistressRecord;
import mse.topology.TopologyLoader;
import mse.topology.TopologyLoader.LoadResult;
import mse.topology.TopologyLoader.NodeJson;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;

/**
 * Central controller application.
 *
 * Startup sequence:
 *   1. Load topology.json → build Graph + NodeState map
 *   2. Validate heartbeat timing constraint
 *   3. Start SerialBridge, HeartbeatService, PathComputationService, DistressHandler
 *   4. Start node-timeout watchdog
 *
 * Packet routing (from serial or in-process simulator):
 *   routing       → updateNodeState() + trigger path recomputation
 *   heartbeat_ack → HeartbeatService.onAckReceived()
 *   distress      → DistressHandler.handle() + send distress_ack
 */
public class Controller {

    private static final Logger LOG = Logger.getLogger(Controller.class.getName());

    private final Graph graph;
    private final Map<String, NodeState> nodeStates;
    private final Properties config;
    private final SerialBridge serial;
    private final HeartbeatService heartbeat;
    private final PathComputationService pathService;
    private final DistressHandler distressHandler;
    private final ScheduledExecutorService watchdog;

    /** Functional interface for in-process packet delivery from Simulator → Controller. */
    public interface PacketSink {
        void deliver(JsonObject packet);
    }

    private volatile PacketSink simulatorSink = null;

    // --- Entry point ---

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: Controller <topology.json> <config.properties>");
            System.exit(1);
        }
        Controller controller = new Controller(Path.of(args[0]), Path.of(args[1]));
        controller.start();
        Thread.currentThread().join();
    }

    // --- Constructor ---

    public Controller(Path topologyFile, Path configFile) throws IOException {
        this.config = loadProperties(configFile);

        long heartbeatIntervalMs = longProp("heartbeat.interval.ms", 5000);
        long nodeTimeoutMs       = longProp("node.timeout.ms", 20000);

        // Spec requirement: node.timeout.ms > 2 × heartbeat.interval.ms
        if (nodeTimeoutMs <= 2 * heartbeatIntervalMs) {
            LOG.severe("FATAL: node.timeout.ms (" + nodeTimeoutMs
                + ") must be > 2 × heartbeat.interval.ms (" + heartbeatIntervalMs + ")");
            System.exit(1);
        }

        LoadResult loaded = TopologyLoader.load(topologyFile);
        this.graph      = loaded.graph;
        this.nodeStates = buildNodeStates(loaded.nodeJsons);

        this.serial = new SerialBridge(
            config.getProperty("serial.port", "/dev/ttyUSB0"),
            this::handlePacket);

        this.heartbeat = new HeartbeatService(
            serial, heartbeatIntervalMs,
            () -> LOG.warning("Gateway unreachable — suspending path-push"),
            () -> LOG.info("Gateway resumed — resuming path-push"));

        long broadcastIntervalMs = longProp("broadcast.interval.ms", 3000);
        this.pathService = new PathComputationService(
            graph, broadcastIntervalMs, this::onPathResults);

        this.distressHandler = new DistressHandler(config);

        this.watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "node-watchdog");
            t.setDaemon(true);
            return t;
        });
    }

    // --- Startup ---

    public void start() {
        serial.start();
        heartbeat.start();

        long nodeTimeoutMs = longProp("node.timeout.ms", 20000);
        watchdog.scheduleAtFixedRate(
            this::checkNodeTimeouts, nodeTimeoutMs, nodeTimeoutMs, TimeUnit.MILLISECONDS);

        distressHandler.start();

        LOG.info("Controller started. Nodes: " + nodeStates.size());
    }

    // --- Packet handling ---

    public void handlePacket(JsonObject packet) {
        String type = packet.has("type") ? packet.get("type").getAsString() : "";
        switch (type) {
            case "routing"       -> handleRouting(packet);
            case "heartbeat_ack" -> heartbeat.onAckReceived();
            case "distress"      -> handleDistress(packet);
            default              -> LOG.fine("Unknown packet type: " + type);
        }
    }

    private void handleRouting(JsonObject p) {
        String nodeId = p.get("node_id").getAsString();
        NodeState state = nodeStates.get(nodeId);
        if (state == null) { LOG.warning("Routing packet from unknown node: " + nodeId); return; }

        boolean wasPassable = state.isPassable;
        state.reportedDistance = p.has("distance") ? p.get("distance").getAsFloat() : Float.MAX_VALUE;
        state.isPassable       = !p.has("is_passable") || p.get("is_passable").getAsBoolean();
        state.sensorError      = p.has("sensor_error") && p.get("sensor_error").getAsBoolean();
        state.temperature      = p.has("temperature") ? p.get("temperature").getAsFloat() : 0f;
        state.co2              = p.has("co2") ? p.get("co2").getAsFloat() : 0f;
        state.topologyCrc32    = p.has("topology_crc32") ? p.get("topology_crc32").getAsString() : null;
        state.lastSeenMs       = System.currentTimeMillis();
        state.timedOut         = false;

        // Sync passability into the graph so Dijkstra sees the change
        graph.getNode(nodeId).ifPresent(n -> {
            if (state.isPassable) n.clearPassableOverride();
            else n.setPassable(false);
        });

        boolean poisonEvent = !state.isPassable || state.reportedDistance == Float.MAX_VALUE;
        boolean recovered   = !wasPassable && state.isPassable;
        if (poisonEvent || recovered) {
            pathService.triggerImmediate();
        } else {
            pathService.scheduleRoutine();
        }
    }

    private void handleDistress(JsonObject p) {
        String nodeId = p.get("node_id").getAsString();
        int seq       = p.get("seq").getAsInt();

        NodeState state = nodeStates.get(nodeId);
        if (state != null) {
            state.distressActive  = true;
            state.lastDistressSeq = seq;
        }

        DistressRecord record = new DistressRecord(
            nodeId, seq,
            p.has("floor") ? p.get("floor").getAsInt() : 0,
            p.has("location_label") ? p.get("location_label").getAsString() : "",
            p.has("timestamp_ms") ? p.get("timestamp_ms").getAsLong() : 0L);
        distressHandler.handle(record);

        // Send distress_ack back through serial and simulator
        JsonObject ack = new JsonObject();
        ack.addProperty("type", "distress_ack");
        ack.addProperty("node_id", nodeId);
        ack.addProperty("seq", seq);
        ack.addProperty("timestamp_ms", System.currentTimeMillis());
        serial.send(ack);
        if (simulatorSink != null) simulatorSink.deliver(ack);
    }

    // --- Path computation results ---

    private void onPathResults(Map<String, PathComputationService.PathResult> results) {
        if (!heartbeat.isGatewayReachable() && serial.isEnabled()) {
            LOG.fine("Gateway unreachable — skipping path-push");
            return;
        }
        for (PathComputationService.PathResult pr : results.values()) {
            NodeState state = nodeStates.get(pr.nodeId());
            if (state == null) continue;
            state.computedNextHopId = pr.nextHopId();
            state.computedDistance  = pr.distance();

            if (pr.nextHopId() == null) continue;  // exit node or unreachable

            JsonObject push = new JsonObject();
            push.addProperty("type", "path_push");
            push.addProperty("node_id", pr.nodeId());
            push.addProperty("next_hop_id", pr.nextHopId());
            push.addProperty("path_distance", pr.distance());
            serial.send(push);
            if (simulatorSink != null) simulatorSink.deliver(push);
        }
    }

    // --- Node timeout watchdog ---

    private void checkNodeTimeouts() {
        long timeoutMs = longProp("node.timeout.ms", 20000);
        boolean anyNewTimeout = false;
        for (NodeState state : nodeStates.values()) {
            if (!state.timedOut && state.lastSeenMs > 0 && !state.isAlive(timeoutMs)) {
                state.timedOut   = true;
                state.isPassable = false;
                graph.getNode(state.nodeId).ifPresent(n -> n.setPassable(false));
                LOG.warning("Node timed out: " + state.nodeId);
                anyNewTimeout = true;
            }
        }
        if (anyNewTimeout) pathService.triggerImmediate();
    }

    // --- Accessors ---

    public Map<String, NodeState> getNodeStates() {
        return Collections.unmodifiableMap(nodeStates);
    }

    public Graph getGraph() {
        return graph;
    }

    public List<DistressRecord> getRecentDistressEvents() {
        return distressHandler.getRecentEvents();
    }

    public void setSimulatorSink(PacketSink sink) {
        this.simulatorSink = sink;
    }

    // --- Helpers ---

    private Map<String, NodeState> buildNodeStates(List<NodeJson> nodeJsons) {
        Map<String, NodeState> map = new LinkedHashMap<>();
        for (NodeJson nj : nodeJsons) {
            map.put(nj.nodeId, new NodeState(
                nj.nodeId, nj.macAddress, nj.floor, nj.locationLabel, nj.isExit));
        }
        return map;
    }

    private Properties loadProperties(Path file) throws IOException {
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            props.load(in);
        }
        return props;
    }

    private long longProp(String key, long def) {
        String val = config.getProperty(key);
        return val != null && !val.isBlank() ? Long.parseLong(val.trim()) : def;
    }
}
