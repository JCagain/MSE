package mse.distress;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Stores distress events in memory for display on the dashboard.
 * Thread-safe. No external notifications (display only).
 */
public class DistressHandler {

    private final List<DistressRecord> recentEvents = new CopyOnWriteArrayList<>();

    public void handle(DistressRecord record) {
        recentEvents.add(0, record);
        if (recentEvents.size() > 100) recentEvents.remove(recentEvents.size() - 1);
    }

    public List<DistressRecord> getRecentEvents() {
        return Collections.unmodifiableList(recentEvents);
    }
}
