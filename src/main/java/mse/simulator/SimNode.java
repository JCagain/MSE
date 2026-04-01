package mse.simulator;

import com.google.gson.JsonObject;
import mse.topology.TopologyLoader.NeighborJson;
import mse.topology.TopologyLoader.NodeJson;

import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * A virtual ESP32 node that simulates all three operating modes:
 *
 *   Normal        — controller is reachable; uses next_hop from path_push.
 *   Mesh fallback — no path_push for PATH_PUSH_TIMEOUT_MS AND gossip coverage ≥ threshold;
 *                   runs distance-vector over gossip from neighbors.
 *   Island fallback — gossip coverage below threshold; same DV but on fewer neighbors.
 *
 * Gossip: each routing broadcast a node sends is forwarded by Simulator to its direct
 * neighbors, which update their gossipTable. If PATH_PUSH_TIMEOUT_MS elapses without a
 * path_push from the controller, recomputeDistance() uses gossip instead.
 */
public class SimNode {

    private static final Logger LOG = Logger.getLogger(SimNode.class.getName());

    // Gossip entry layout: [0]=distance, [1]=isPassable (1.0=true, 0.0=false), [2]=lastSeenMs
    private static final long GOSSIP_TIMEOUT_MS    = 15_000;
    private static final long PATH_PUSH_TIMEOUT_MS = 15_000;

    private final NodeJson meta;
    private final List<NeighborJson> neighbors;
    private final long broadcastIntervalMs;
    private final Consumer<JsonObject> toController;
    private final int totalNodeCount;
    private final int meshFallbackMinCoveragePct;

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

    // Fallback state
    private final Map<String, float[]> gossipTable = new ConcurrentHashMap<>();
    private volatile long lastPathPushMs = 0;

    private ScheduledExecutorService scheduler;

    public SimNode(NodeJson meta, long broadcastIntervalMs, Consumer<JsonObject> toController,
                   int totalNodeCount, int meshFallbackMinCoveragePct) {
        this.meta = meta;
        this.neighbors = meta.neighbors;
        this.broadcastIntervalMs = broadcastIntervalMs;
        this.toController = toController;
        this.totalNodeCount = totalNodeCount;
        this.meshFallbackMinCoveragePct = meshFallbackMinCoveragePct;
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
        if (meta.isExit) {
            myDistance = isPassable ? 0f : Float.MAX_VALUE;
        } else {
            recomputeDistance();
        }

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

    private void recomputeDistance() {
        boolean controllerReachable = (lastPathPushMs > 0)
            && (System.currentTimeMillis() - lastPathPushMs) < PATH_PUSH_TIMEOUT_MS;
        if (controllerReachable) {
            // Normal mode: distance is set by path_push from controller — do not override.
            return;
        }

        // Purge stale gossip entries
        long now = System.currentTimeMillis();
        gossipTable.entrySet().removeIf(e -> (now - (long) e.getValue()[2]) > GOSSIP_TIMEOUT_MS);

        int coveragePct = totalNodeCount > 0 ? (gossipTable.size() * 100 / totalNodeCount) : 0;

        if (coveragePct >= meshFallbackMinCoveragePct) {
            LOG.fine(meta.nodeId + " mesh-fallback DV (gossip coverage=" + coveragePct + "%)");
        } else {
            LOG.fine(meta.nodeId + " island-fallback DV (gossip coverage=" + coveragePct + "%)");
        }
        // Both modes use distance-vector over available gossip.
        // A full local Dijkstra would need gossiping the full topology (out of scope for course).
        runDistanceVector();
    }

    /**
     * Distance-vector update: my distance = min over passable neighbors of (neighbor.dist + edge).
     */
    private void runDistanceVector() {
        float best = Float.MAX_VALUE;
        for (NeighborJson nb : neighbors) {
            float[] gossip = gossipTable.get(nb.nodeId);
            if (gossip == null) continue;
            if (gossip[1] < 0.5f) continue;          // neighbor reports impassable
            if (gossip[0] == Float.MAX_VALUE) continue; // neighbor has no path either
            float candidate = gossip[0] + nb.edgeWeight;
            if (candidate < best) best = candidate;
        }
        myDistance = isPassable ? best : Float.MAX_VALUE;
    }

    /** Called by Simulator when the Controller sends a packet addressed to this node. */
    public void receive(JsonObject packet) {
        String type = packet.has("type") ? packet.get("type").getAsString() : "";
        switch (type) {
            case "routing" -> {
                // Gossip from a direct neighbor (delivered by Simulator fan-out)
                String senderId = packet.get("node_id").getAsString();
                if (!senderId.equals(meta.nodeId)) {
                    float dist     = packet.has("distance")
                        ? packet.get("distance").getAsFloat() : Float.MAX_VALUE;
                    float passable = (packet.has("is_passable")
                        && packet.get("is_passable").getAsBoolean()) ? 1f : 0f;
                    gossipTable.put(senderId,
                        new float[]{ dist, passable, System.currentTimeMillis() });
                }
            }
            case "path_push" -> {
                if (meta.nodeId.equals(packet.get("node_id").getAsString())) {
                    nextHopId      = packet.has("next_hop_id")
                        ? packet.get("next_hop_id").getAsString() : null;
                    myDistance     = packet.get("path_distance").getAsFloat();
                    lastPathPushMs = System.currentTimeMillis();
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
