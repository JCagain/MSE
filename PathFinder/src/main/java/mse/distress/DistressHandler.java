package mse.distress;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;

/**
 * Handles distress events:
 *   1. Appends a JSON-Lines entry to distress-log.jsonl
 *   2. Sends SMS via Twilio (if credentials configured in config.properties)
 *   3. HTTP POSTs to api.endpoint (if configured)
 *   4. Failed notifications enter a retry queue; the queue is persisted to
 *      distress-retry-queue.json on shutdown and reloaded on startup.
 */
public class DistressHandler {

    private static final Logger LOG       = Logger.getLogger(DistressHandler.class.getName());
    private static final Path   LOG_FILE  = Path.of("distress-log.jsonl");
    private static final Path   QUEUE_FILE = Path.of("distress-retry-queue.json");

    private final Properties config;
    private final Gson gson = new Gson();
    private final List<DistressRecord> recentEvents = new CopyOnWriteArrayList<>();
    private final Queue<DistressRecord> retryQueue  = new ConcurrentLinkedQueue<>();

    private HttpClient httpClient;
    private ScheduledExecutorService retryScheduler;

    public DistressHandler(Properties config) {
        this.config = config;
    }

    public void start() {
        httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(longProp("api.timeout.ms", 5000)))
            .build();

        loadRetryQueue();

        long retryIntervalMs = longProp("controller.distress.notification.retry.interval.ms", 10_000);
        retryScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "distress-retry");
            t.setDaemon(true);
            return t;
        });
        retryScheduler.scheduleAtFixedRate(
            this::drainRetryQueue, retryIntervalMs, retryIntervalMs, TimeUnit.MILLISECONDS);

        LOG.info("DistressHandler started — log=" + LOG_FILE
            + "  retryQueue=" + QUEUE_FILE
            + "  retryInterval=" + retryIntervalMs + "ms");
    }

    public void handle(DistressRecord record) {
        // 1. In-memory recent list (newest first, cap at 100)
        recentEvents.add(0, record);
        if (recentEvents.size() > 100) recentEvents.remove(recentEvents.size() - 1);

        LOG.info("DISTRESS: node=" + record.nodeId
            + " floor=" + record.floor
            + " location=" + record.locationLabel
            + " seq=" + record.seq);

        // 2. Append to disk log
        appendToLog(record);

        // 3. Notify immediately; queue on failure
        if (!sendNotification(record)) {
            retryQueue.offer(record);
            LOG.warning("Notification failed — queued for retry (queue=" + retryQueue.size() + ")");
        }
    }

    public List<DistressRecord> getRecentEvents() {
        return Collections.unmodifiableList(recentEvents);
    }

    public void stop() {
        if (retryScheduler != null) retryScheduler.shutdownNow();
        drainRetryQueue();    // one final attempt before persisting
        persistRetryQueue();
    }

    // -------------------------------------------------------------------------
    // Disk log
    // -------------------------------------------------------------------------

    private void appendToLog(DistressRecord r) {
        try {
            Files.writeString(LOG_FILE, gson.toJson(r) + System.lineSeparator(),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            LOG.warning("distress log write failed: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Notification
    // -------------------------------------------------------------------------

    /**
     * Sends via all configured channels.
     * Returns true if no channels are configured, or if every configured channel succeeds.
     */
    private boolean sendNotification(DistressRecord record) {
        boolean anyConfigured = false;
        boolean allSucceeded  = true;

        String recipients = config.getProperty("sms.recipients", "").trim();
        if (!recipients.isBlank()) {
            anyConfigured = true;
            allSucceeded &= sendSms(record, recipients.split(","));
        }

        String endpoint = config.getProperty("api.endpoint", "").trim();
        if (!endpoint.isBlank()) {
            anyConfigured = true;
            allSucceeded &= httpPost(record, endpoint);
        }

        return !anyConfigured || allSucceeded;
    }

    private boolean sendSms(DistressRecord record, String[] numbers) {
        String sid   = config.getProperty("twilio.account.sid",  "").trim();
        String token = config.getProperty("twilio.auth.token",   "").trim();
        String from  = config.getProperty("twilio.from.number",  "").trim();
        if (sid.isBlank() || token.isBlank() || from.isBlank()) {
            LOG.fine("Twilio credentials not configured — skipping SMS");
            return true;   // not a failure; channel simply isn't active
        }
        try {
            com.twilio.Twilio.init(sid, token);
            String body = "[MSE DISTRESS] Node " + record.nodeId
                + "  Floor " + record.floor
                + "  " + record.locationLabel
                + "  seq=" + record.seq;
            for (String rawNumber : numbers) {
                String to = rawNumber.trim();
                if (to.isBlank()) continue;
                com.twilio.rest.api.v2010.account.Message.creator(
                    new com.twilio.type.PhoneNumber(to),
                    new com.twilio.type.PhoneNumber(from),
                    body
                ).create();
                LOG.info("SMS sent to " + to);
            }
            return true;
        } catch (Exception e) {
            LOG.warning("SMS send failed: " + e.getMessage());
            return false;
        }
    }

    private boolean httpPost(DistressRecord record, String endpoint) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(Duration.ofMillis(longProp("api.timeout.ms", 5000)))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(record)))
                .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                LOG.info("HTTP POST distress OK (HTTP " + resp.statusCode() + ")");
                return true;
            }
            LOG.warning("HTTP POST distress rejected: HTTP " + resp.statusCode());
            return false;
        } catch (Exception e) {
            LOG.warning("HTTP POST distress error: " + e.getMessage());
            return false;
        }
    }

    // -------------------------------------------------------------------------
    // Retry queue
    // -------------------------------------------------------------------------

    private void drainRetryQueue() {
        if (retryQueue.isEmpty()) return;
        LOG.info("Distress retry: attempting " + retryQueue.size() + " queued records");
        List<DistressRecord> stillFailing = new ArrayList<>();
        DistressRecord r;
        while ((r = retryQueue.poll()) != null) {
            if (!sendNotification(r)) stillFailing.add(r);
        }
        retryQueue.addAll(stillFailing);
        if (!stillFailing.isEmpty()) {
            LOG.warning("Distress retry: " + stillFailing.size() + " still failing");
        }
    }

    private void persistRetryQueue() {
        if (retryQueue.isEmpty()) {
            try { Files.deleteIfExists(QUEUE_FILE); } catch (IOException ignored) {}
            return;
        }
        try {
            Files.writeString(QUEUE_FILE, gson.toJson(retryQueue));
            LOG.info("Persisted " + retryQueue.size() + " retry record(s) to " + QUEUE_FILE);
        } catch (IOException e) {
            LOG.warning("Failed to persist retry queue: " + e.getMessage());
        }
    }

    private void loadRetryQueue() {
        if (!Files.exists(QUEUE_FILE)) return;
        try {
            JsonArray arr = JsonParser.parseString(Files.readString(QUEUE_FILE)).getAsJsonArray();
            int count = 0;
            for (JsonElement el : arr) {
                retryQueue.offer(gson.fromJson(el, DistressRecord.class));
                count++;
            }
            LOG.info("Loaded " + count + " distress retry record(s) from " + QUEUE_FILE);
        } catch (Exception e) {
            LOG.warning("Failed to load retry queue: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private long longProp(String key, long def) {
        String val = config.getProperty(key);
        return val != null && !val.isBlank() ? Long.parseLong(val.trim()) : def;
    }
}
