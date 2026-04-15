package mse.controller;

import com.fazecast.jSerialComm.SerialPort;
import com.google.gson.*;
import mse.Graph;
import mse.Node;
import mse.dashboard.SwingDashboard;
import mse.distress.DistressHandler;
import mse.distress.DistressRecord;
import mse.topology.TopologyLoader;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Laptop-side entry point.
 *
 * Opens USB serial to two ESP32 nodes directly (no Arduino Mega).
 * Reads newline-JSON packets from each ESP32 in background threads:
 *   node_state -> update Node sensor values, run Dijkstra, send path_push back
 *   distress   -> record event, send distress_ack back to same port
 *
 * Expected packet from ESP32:
 *   {"type":"node_state","node_id":"1B","temp":25.0,"co2":0.08}
 *
 * Sent to ESP32 after Dijkstra:
 *   {"type":"path_push","node_id":"1B","direction":"right"}
 *
 * Port-to-node mapping is learned dynamically: whichever port sends a
 * node_state for a given node_id owns that node's path_push writer.
 */
public class EspController implements DashboardDataSource {

    private static final Logger LOG = Logger.getLogger(EspController.class.getName());
    private static final int BAUD_RATE = 115200;

    private final Graph graph;
    private final Map<String, NodeState> nodeStates;
    private final DistressHandler distressHandler = new DistressHandler();
    private final List<String> portNames;

    // nodeId -> writer for the port that ESP32 is connected on (learned at runtime)
    private final Map<String, PrintWriter> nodeWriters = new ConcurrentHashMap<>();

    // fromNodeId -> (toNodeId -> direction label)
    private final Map<String, Map<String, String>> directionMap;

    private SwingDashboard dashboard;
    private volatile boolean running;

    // --- Entry point ---

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: EspController <topology.json> <config.properties>");
            System.exit(1);
        }
        Properties config = new Properties();
        try (InputStream in = new FileInputStream(args[1])) { config.load(in); }
        TopologyLoader.LoadResult loaded = TopologyLoader.load(Path.of(args[0]), config);
        List<String> ports = List.of(
            config.getProperty("serial.port.1", "/dev/ttyUSB0"),
            config.getProperty("serial.port.2", "/dev/ttyUSB1")
        );
        EspController bridge = new EspController(loaded.graph, loaded.nodeJsons, ports);
        bridge.start();
        Thread.currentThread().join();
    }

    // --- Constructor (no I/O — safe for tests) ---

    public EspController(Graph graph, List<TopologyLoader.NodeJson> nodeJsons, List<String> portNames) {
        this.graph     = graph;
        this.portNames = portNames;
        this.nodeStates = new LinkedHashMap<>();
        for (TopologyLoader.NodeJson nj : nodeJsons) {
            nodeStates.put(nj.nodeId, new NodeState(
                nj.nodeId, nj.macAddress, nj.floor, nj.locationLabel, nj.isExit));
        }
        this.directionMap = buildDirectionMap(nodeJsons);
    }

    private static Map<String, Map<String, String>> buildDirectionMap(
            List<TopologyLoader.NodeJson> nodeJsons) {
        Map<String, Map<String, String>> map = new HashMap<>();
        for (TopologyLoader.NodeJson nj : nodeJsons) {
            Map<String, String> toDir = new HashMap<>();
            for (TopologyLoader.NeighborJson nb : nj.neighbors) {
                if (nb.direction != null) toDir.put(nb.nodeId, nb.direction);
            }
            map.put(nj.nodeId, toDir);
        }
        return map;
    }

    // --- Startup ---

    public void start() {
        running = true;
        for (String portName : portNames) {
            SerialPort port = SerialPort.getCommPort(portName);
            if (port.openPort()) {
                port.setBaudRate(BAUD_RATE);
                port.setComPortTimeouts(SerialPort.TIMEOUT_READ_BLOCKING, 0, 0);
                PrintWriter writer = new PrintWriter(new OutputStreamWriter(
                    port.getOutputStream(), StandardCharsets.UTF_8), true);
                Thread reader = new Thread(
                    () -> readLoop(port, writer), "esp-reader-" + portName);
                reader.setDaemon(true);
                reader.start();
                LOG.info("Connected to ESP32 on " + portName);
            } else {
                LOG.warning("Cannot open " + portName + " -- port skipped");
            }
        }
        dashboard = new SwingDashboard(this);
        dashboard.start();
    }

    // --- Serial reader ---

    private void readLoop(SerialPort port, PrintWriter writer) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(port.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while (running && (line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty()) handleLine(line, writer);
            }
        } catch (IOException e) {
            if (running) LOG.warning("Serial read error on " + port.getSystemPortName()
                + ": " + e.getMessage());
        }
    }

    // --- Packet dispatch ---

    synchronized void handleLine(String line, PrintWriter writer) {
        JsonObject packet;
        try {
            packet = JsonParser.parseString(line).getAsJsonObject();
        } catch (Exception e) {
            LOG.warning("Malformed packet: " + line);
            return;
        }
        String type = packet.has("type") ? packet.get("type").getAsString() : "";
        switch (type) {
            case "node_state" -> handleNodeState(packet, writer);
            case "distress"   -> handleDistress(packet, writer);
            default           -> LOG.fine("Unknown packet: " + line);
        }
    }

    void handleNodeState(JsonObject packet, PrintWriter writer) {
        String nodeId = packet.get("node_id").getAsString();
        float temp = packet.get("temp").getAsFloat();
        float co2  = packet.get("co2").getAsFloat();

        // Remember which port this node is on
        nodeWriters.put(nodeId, writer);

        // Update the graph node so Dijkstra sees current sensor values
        graph.getNode(nodeId).ifPresent(node -> {
            node.setTemperature(temp);
            node.setGasConcentration(co2);
        });

        // Update dashboard state
        NodeState state = nodeStates.get(nodeId);
        if (state != null) {
            state.temperature = temp;
            state.co2         = co2;
            state.isPassable  = graph.getNode(nodeId)
                .map(Node::isPassable).orElse(true);
            state.lastSeenMs  = System.currentTimeMillis();
            state.timedOut    = false;
        }

        runDijkstraAndPush();
        if (dashboard != null) dashboard.push();
    }

    void handleDistress(JsonObject packet, PrintWriter writer) {
        DistressRecord record = new DistressRecord(
            packet.get("node_id").getAsString(),
            packet.get("seq").getAsInt(),
            packet.has("floor")          ? packet.get("floor").getAsInt()             : 0,
            packet.has("location_label") ? packet.get("location_label").getAsString() : "",
            packet.has("timestamp_ms")   ? packet.get("timestamp_ms").getAsLong()     : 0L);
        distressHandler.handle(record);
        sendDistressAck(record, writer);
        if (dashboard != null) dashboard.push();
    }

    private void sendDistressAck(DistressRecord record, PrintWriter writer) {
        JsonObject ack = new JsonObject();
        ack.addProperty("type",         "distress_ack");
        ack.addProperty("node_id",      record.nodeId);
        ack.addProperty("seq",          record.seq);
        ack.addProperty("timestamp_ms", System.currentTimeMillis());
        writer.println(ack);
    }

    // --- Dijkstra + path_push ---

    private void runDijkstraAndPush() {
        Map<String, PathComputationService.PathResult> results =
            PathComputationService.compute(graph);

        for (Map.Entry<String, PathComputationService.PathResult> entry : results.entrySet()) {
            String nodeId = entry.getKey();
            PathComputationService.PathResult result = entry.getValue();
            NodeState state = nodeStates.get(nodeId);
            if (state == null) continue;

            state.isPassable        = graph.getNode(nodeId).map(Node::isPassable).orElse(true);
            state.computedNextHopId = result.nextHopId();
            state.computedDistance  = result.distance();

            // Send path_push to this node's ESP32 if it is wired to the laptop
            if (result.nextHopId() == null) continue;
            PrintWriter writer = nodeWriters.get(nodeId);
            if (writer == null) continue;

            String direction = directionMap
                .getOrDefault(nodeId, Collections.emptyMap())
                .get(result.nextHopId());
            if (direction == null) {
                LOG.warning("No direction for edge " + nodeId + " -> " + result.nextHopId());
                continue;
            }

            JsonObject push = new JsonObject();
            push.addProperty("type",      "path_push");
            push.addProperty("node_id",   nodeId);
            push.addProperty("direction", direction);
            writer.println(push);
        }
    }

    // --- DashboardDataSource ---

    @Override public Map<String, NodeState> getNodeStates() {
        return Collections.unmodifiableMap(nodeStates);
    }
    @Override public Graph getGraph()  { return graph; }
    @Override public List<DistressRecord> getRecentDistressEvents() {
        return distressHandler.getRecentEvents();
    }
}
