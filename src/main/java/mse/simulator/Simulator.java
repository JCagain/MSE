package mse.simulator;

import com.google.gson.JsonObject;
import mse.controller.Controller;
import mse.topology.TopologyLoader;
import mse.topology.TopologyLoader.NodeJson;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Manages a set of SimNodes and wires them in-process to the Controller.
 *
 * Packet flow:
 *   SimNode → Simulator.toController() → Controller.handlePacket()
 *   Controller.onPathResults() → Controller.simulatorSink → Simulator.fromController() → SimNode.receive()
 *
 * Gossip delivery (for fallback modes):
 *   When a SimNode sends a "routing" packet, Simulator also delivers it to that node's
 *   direct neighbors so they can update their gossipTable.
 *
 * No serial bridge is needed when running all-simulated.
 */
public class Simulator {

    private static final Logger LOG = Logger.getLogger(Simulator.class.getName());

    private final Map<String, SimNode> nodes = new LinkedHashMap<>();
    private final Map<String, List<String>> neighborMap;   // nodeId → direct neighbor IDs
    private final Controller controller;

    public Simulator(Path topologyFile, Controller controller, Properties config) throws IOException {
        this.controller = controller;

        long broadcastIntervalMs = longProp(config, "broadcast.interval.ms", 3000);
        int meshFallbackPct      = intProp(config,  "mesh.fallback.min.coverage.pct", 50);

        TopologyLoader.LoadResult loaded = TopologyLoader.load(topologyFile);
        int totalNodeCount = loaded.nodeJsons.size();

        neighborMap = new HashMap<>();
        for (NodeJson nj : loaded.nodeJsons) {
            List<String> nids = nj.neighbors.stream()
                .map(nb -> nb.nodeId)
                .collect(Collectors.toList());
            neighborMap.put(nj.nodeId, nids);
        }

        Set<String> hwNodes = parseHwNodes(config);
        for (NodeJson nj : loaded.nodeJsons) {
            if (hwNodes.contains(nj.nodeId)) {
                LOG.info("Simulator skipping hardware node: " + nj.nodeId);
                continue;
            }
            nodes.put(nj.nodeId, new SimNode(
                nj, broadcastIntervalMs, this::toController,
                totalNodeCount, meshFallbackPct));
        }

        controller.setSimulatorSink(this::fromController);
        LOG.info("Simulator created " + nodes.size() + " virtual nodes"
            + "  meshFallbackMinCoverage=" + meshFallbackPct + "%");
    }

    public void start() {
        nodes.values().forEach(SimNode::start);
        LOG.info("Simulator started");
    }

    public void stop() {
        nodes.values().forEach(SimNode::stop);
    }

    /** SimNode → Controller (in-process) + gossip fan-out to direct neighbors. */
    private void toController(JsonObject packet) {
        controller.handlePacket(packet);

        // Fan routing packets to direct neighbors as gossip
        String type = packet.has("type") ? packet.get("type").getAsString() : "";
        if ("routing".equals(type)) {
            String senderId = packet.has("node_id") ? packet.get("node_id").getAsString() : null;
            if (senderId != null) {
                for (String nid : neighborMap.getOrDefault(senderId, List.of())) {
                    SimNode neighbor = nodes.get(nid);
                    if (neighbor != null) neighbor.receive(packet);
                }
            }
        }
    }

    /**
     * Controller → SimNode (in-process).
     * Routes path_push and distress_ack to the addressed node.
     */
    public void fromController(JsonObject packet) {
        String targetId = packet.has("node_id") ? packet.get("node_id").getAsString() : null;
        if (targetId != null) {
            SimNode target = nodes.get(targetId);
            if (target != null) target.receive(packet);
        }
    }

    public SimNode getNode(String nodeId) { return nodes.get(nodeId); }
    public Collection<SimNode> getAllNodes() { return Collections.unmodifiableCollection(nodes.values()); }

    // --- Entry point for running controller + simulator together ---

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: Simulator <topology.json> <config.properties>");
            System.exit(1);
        }
        Path topologyFile = Path.of(args[0]);
        Path configFile   = Path.of(args[1]);

        Properties config = new Properties();
        try (InputStream in = Files.newInputStream(configFile)) { config.load(in); }

        Controller controller = new Controller(topologyFile, configFile);
        Simulator  simulator  = new Simulator(topologyFile, controller, config);

        controller.start();
        simulator.start();

        LOG.info("Controller + Simulator running. Press Ctrl-C to stop.");
        Thread.currentThread().join();
    }

    // --- Helpers ---

    private static Set<String> parseHwNodes(Properties config) {
        String val = config.getProperty("hardware.nodes", "").trim();
        if (val.isBlank()) return Set.of();
        return Arrays.stream(val.split(","))
            .map(String::trim)
            .filter(s -> !s.isBlank())
            .collect(Collectors.toSet());
    }

    private static long longProp(Properties p, String key, long def) {
        String val = p.getProperty(key);
        return val != null && !val.isBlank() ? Long.parseLong(val.trim()) : def;
    }

    private static int intProp(Properties p, String key, int def) {
        String val = p.getProperty(key);
        return val != null && !val.isBlank() ? Integer.parseInt(val.trim()) : def;
    }
}
