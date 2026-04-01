package mse.simulator;

import com.google.gson.JsonObject;
import mse.topology.TopologyLoader.NeighborJson;
import mse.topology.TopologyLoader.NodeJson;

import java.util.List;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * A virtual ESP32 node running Normal mode routing.
 *
 * Behaviour:
 *   - Sends a routing packet to the controller in-process every broadcastIntervalMs.
 *   - Receives path_push packets and stores next_hop.
 *   - Receives distress_ack packets and clears distress state.
 *   - Sensor values (temperature, co2) are settable at runtime to simulate hazards.
 *
 * Mesh and Island fallback modes are added in Task 16.
 */
public class SimNode {

    private static final Logger LOG = Logger.getLogger(SimNode.class.getName());

    private final NodeJson meta;
    private final List<NeighborJson> neighbors;
    private final long broadcastIntervalMs;
    private final Consumer<JsonObject> toController;

    // Sensor state — settable by ScenarioRunner
    private volatile float temperature = 25f;
    private volatile float co2 = 0.1f;

    // Routing state
    private volatile float myDistance = Float.MAX_VALUE;
    private volatile boolean isPassable = true;
    private volatile String nextHopId = null;

    // Distress state
    private volatile boolean distressPending = false;
    private volatile int distressSeq = 0;

    private ScheduledExecutorService scheduler;

    public SimNode(NodeJson meta, long broadcastIntervalMs, Consumer<JsonObject> toController) {
        this.meta = meta;
        this.neighbors = meta.neighbors;
        this.broadcastIntervalMs = broadcastIntervalMs;
        this.toController = toController;
        if (meta.isExit) this.myDistance = 0f;
    }

    public void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sim-" + meta.nodeId);
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::broadcast, 0, broadcastIntervalMs, TimeUnit.MILLISECONDS);
    }

    private void broadcast() {
        isPassable = (temperature <= 60f) && (co2 <= 0.5f);
        if (meta.isExit) myDistance = isPassable ? 0f : Float.MAX_VALUE;

        JsonObject pkt = new JsonObject();
        pkt.addProperty("type", "routing");
        pkt.addProperty("node_id", meta.nodeId);
        pkt.addProperty("distance", myDistance);
        pkt.addProperty("is_passable", isPassable);
        pkt.addProperty("sensor_error", false);
        pkt.addProperty("temperature", temperature);
        pkt.addProperty("co2", co2);
        pkt.addProperty("timestamp_ms", System.currentTimeMillis());
        toController.accept(pkt);
    }

    /** Called by Simulator when the Controller sends a packet addressed to this node. */
    public void receive(JsonObject packet) {
        String type = packet.has("type") ? packet.get("type").getAsString() : "";
        switch (type) {
            case "path_push" -> {
                if (meta.nodeId.equals(packet.get("node_id").getAsString())) {
                    nextHopId  = packet.has("next_hop_id") ? packet.get("next_hop_id").getAsString() : null;
                    myDistance = packet.get("path_distance").getAsFloat();
                    LOG.fine(meta.nodeId + " next_hop=" + nextHopId + " dist=" + myDistance);
                }
            }
            case "distress_ack" -> {
                if (meta.nodeId.equals(packet.get("node_id").getAsString())) {
                    int ackSeq = packet.get("seq").getAsInt();
                    if (ackSeq == distressSeq) {
                        distressPending = false;
                        LOG.info(meta.nodeId + " distress ack received for seq=" + ackSeq);
                    }
                }
            }
        }
    }

    /** Simulates pressing the help button on this node. */
    public void pressHelpButton() {
        distressSeq++;
        distressPending = true;
        JsonObject pkt = new JsonObject();
        pkt.addProperty("type", "distress");
        pkt.addProperty("node_id", meta.nodeId);
        pkt.addProperty("seq", distressSeq);
        pkt.addProperty("floor", meta.floor);
        pkt.addProperty("location_label", meta.locationLabel);
        pkt.addProperty("timestamp_ms", System.currentTimeMillis());
        toController.accept(pkt);
        LOG.info(meta.nodeId + " help button pressed (seq=" + distressSeq + ")");
    }

    public void stop() {
        if (scheduler != null) scheduler.shutdownNow();
    }

    // Sensor setters (used by ScenarioRunner)
    public void setTemperature(float t) { this.temperature = t; }
    public void setCo2(float c) { this.co2 = c; }

    // Getters
    public String getNodeId()    { return meta.nodeId; }
    public String getNextHopId() { return nextHopId; }
    public float getDistance()   { return myDistance; }
    public boolean isPassable()  { return isPassable; }
    public boolean isDistressPending() { return distressPending; }
}
