package mse.simulator;

import com.google.gson.JsonObject;
import mse.controller.Controller;
import mse.topology.TopologyLoader;
import mse.topology.TopologyLoader.NodeJson;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;

/**
 * Manages a set of SimNodes and wires them in-process to the Controller.
 *
 * Packet flow:
 *   SimNode → Simulator.toController() → Controller.handlePacket()
 *   Controller.onPathResults() → Controller.simulatorSink → Simulator.fromController() → SimNode.receive()
 *
 * No serial bridge is needed when running all-simulated.
 */
public class Simulator {

    private static final Logger LOG = Logger.getLogger(Simulator.class.getName());

    private final Map<String, SimNode> nodes = new LinkedHashMap<>();
    private final Controller controller;

    public Simulator(Path topologyFile, Controller controller) throws IOException {
        this.controller = controller;

        long broadcastIntervalMs = 3000;
        TopologyLoader.LoadResult loaded = TopologyLoader.load(topologyFile);
        for (NodeJson nj : loaded.nodeJsons) {
            nodes.put(nj.nodeId, new SimNode(nj, broadcastIntervalMs, this::toController));
        }

        controller.setSimulatorSink(this::fromController);
        LOG.info("Simulator created " + nodes.size() + " virtual nodes");
    }

    public void start() {
        nodes.values().forEach(SimNode::start);
        LOG.info("Simulator started");
    }

    public void stop() {
        nodes.values().forEach(SimNode::stop);
    }

    /** SimNode → Controller (in-process). */
    private void toController(JsonObject packet) {
        controller.handlePacket(packet);
    }

    /**
     * Controller → SimNode (in-process).
     * Routes path_push and distress_ack to the addressed node.
     */
    public void fromController(JsonObject packet) {
        String type = packet.has("type") ? packet.get("type").getAsString() : "";
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

        Controller controller = new Controller(topologyFile, configFile);
        Simulator simulator   = new Simulator(topologyFile, controller);

        controller.start();
        simulator.start();

        LOG.info("Controller + Simulator running. Press Ctrl-C to stop.");
        Thread.currentThread().join();
    }
}
