package mse.distress;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Stores distress events in memory for display on the dashboard.
 * Thread-safe. No external notifications (display only).
 */
public class DistressHandler {

    private final List<DistressRecord> recentEvents = new ArrayList<>();

    public synchronized void handle(DistressRecord record) {
        recentEvents.add(0, record);
        if (recentEvents.size() > 100) recentEvents.remove(recentEvents.size() - 1);
    }

    public synchronized List<DistressRecord> getRecentEvents() {
        return Collections.unmodifiableList(new ArrayList<>(recentEvents));
    }
}
