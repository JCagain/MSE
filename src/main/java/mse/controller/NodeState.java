package mse.controller;

/**
 * The controller's runtime view of a single mesh node.
 *
 * Static fields are populated from topology.json on startup.
 * Dynamic fields are updated by incoming routing packets.
 * computedNextHopId and computedDistance are written by PathComputationService.
 */
public class NodeState {

    // --- Static (from topology.json) ---
    public final String nodeId;
    public final String macAddress;
    public final int floor;
    public final String locationLabel;
    public final boolean isExit;

    // --- Dynamic (from routing packets) ---
    public float reportedDistance = Float.MAX_VALUE;
    public boolean isPassable = true;
    public boolean sensorError = false;
    public float temperature = 0f;
    public float co2 = 0f;
    public String reportedNextHopId = null;
    public String topologyCrc32 = null;
    public long lastSeenMs = 0;
    public boolean timedOut = false;

    // --- Computed (from PathComputationService) ---
    public String computedNextHopId = null;
    public float computedDistance = Float.MAX_VALUE;

    // --- Distress ---
    public boolean distressActive = false;
    public int lastDistressSeq = -1;

    public NodeState(String nodeId, String macAddress, int floor,
                     String locationLabel, boolean isExit) {
        this.nodeId = nodeId;
        this.macAddress = macAddress;
        this.floor = floor;
        this.locationLabel = locationLabel;
        this.isExit = isExit;
    }

    /** Returns true if this node was heard from within the given timeout window. */
    public boolean isAlive(long timeoutMs) {
        if (lastSeenMs == 0) return false;
        return (System.currentTimeMillis() - lastSeenMs) < timeoutMs;
    }
}
