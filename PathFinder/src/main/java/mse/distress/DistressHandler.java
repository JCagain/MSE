package mse.distress;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

/** Stub — full implementation in Task 15. */
public class DistressHandler {

    private static final Logger LOG = Logger.getLogger(DistressHandler.class.getName());
    private final Properties config;
    private final List<DistressRecord> recentEvents = new CopyOnWriteArrayList<>();

    public DistressHandler(Properties config) {
        this.config = config;
    }

    public void start() {
        LOG.info("DistressHandler started (stub)");
    }

    public void handle(DistressRecord record) {
        recentEvents.add(0, record);
        if (recentEvents.size() > 100) recentEvents.remove(recentEvents.size() - 1);
        LOG.info("DISTRESS: " + record.nodeId + " floor=" + record.floor
            + " location=" + record.locationLabel + " seq=" + record.seq);
    }

    public List<DistressRecord> getRecentEvents() {
        return Collections.unmodifiableList(recentEvents);
    }

    public void stop() {}
}
