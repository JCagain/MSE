package mse.controller;

/**
 * The laptop's runtime view of a single mesh node.
 *
 * Static fields are populated from topology.json on startup.
 * Dynamic fields are updated from state_snapshot packets received from the Mega.
 */
public class NodeState {

    // --- Static (from topology.json) ---
    public final String nodeId;
    public final String macAddress;
    public final int floor;
    public final String locationLabel;
    public final boolean isExit;

    // --- Dynamic (from state_snapshot packets) ---
    public boolean isPassable = true;
    public float temperature = 0f;
    public float co2 = 0f;
    public long lastSeenMs = 0;
    public boolean timedOut = false;
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
}
