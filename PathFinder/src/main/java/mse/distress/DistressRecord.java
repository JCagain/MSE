package mse.distress;

/** Immutable value object representing a single distress event. */
public class DistressRecord {
    public final String nodeId;
    public final int seq;
    public final int floor;
    public final String locationLabel;
    public final long nodeTimestampMs;
    public final long receivedAtMs;

    public DistressRecord(String nodeId, int seq, int floor,
                          String locationLabel, long nodeTimestampMs) {
        this.nodeId          = nodeId;
        this.seq             = seq;
        this.floor           = floor;
        this.locationLabel   = locationLabel;
        this.nodeTimestampMs = nodeTimestampMs;
        this.receivedAtMs    = System.currentTimeMillis();
    }
}
