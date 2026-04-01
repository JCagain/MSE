package mse.controller;

import com.google.gson.JsonObject;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Sends a heartbeat packet to the gateway every heartbeatIntervalMs.
 * Tracks the last time a heartbeat_ack was received.
 *
 * If no ack arrives within heartbeatIntervalMs × 2, calls onGatewayUnreachable().
 * Calls onGatewayResumed() when acks resume after a gap.
 */
public class HeartbeatService {

    private static final Logger LOG = Logger.getLogger(HeartbeatService.class.getName());

    private final SerialBridge serial;
    private final long heartbeatIntervalMs;
    private final Runnable onGatewayUnreachable;
    private final Runnable onGatewayResumed;

    private volatile long lastAckMs = 0;
    private volatile boolean gatewayReachable = true;
    private ScheduledExecutorService scheduler;

    public HeartbeatService(SerialBridge serial, long heartbeatIntervalMs,
                             Runnable onGatewayUnreachable, Runnable onGatewayResumed) {
        this.serial = serial;
        this.heartbeatIntervalMs = heartbeatIntervalMs;
        this.onGatewayUnreachable = onGatewayUnreachable;
        this.onGatewayResumed = onGatewayResumed;
    }

    public void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "heartbeat");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::tick, 0, heartbeatIntervalMs, TimeUnit.MILLISECONDS);
        LOG.info("HeartbeatService started (interval=" + heartbeatIntervalMs + "ms)");
    }

    private void tick() {
        // Send heartbeat
        JsonObject hb = new JsonObject();
        hb.addProperty("type", "heartbeat");
        hb.addProperty("timestamp_ms", System.currentTimeMillis());
        serial.send(hb);

        // Check ack status — unreachable if serial is enabled but no ack for 2× interval
        if (!serial.isEnabled()) return;

        long now = System.currentTimeMillis();
        boolean neverAcked = lastAckMs == 0;
        boolean ackMissing = !neverAcked && (now - lastAckMs) > heartbeatIntervalMs * 2;

        if ((neverAcked || ackMissing) && gatewayReachable) {
            gatewayReachable = false;
            LOG.warning("Gateway unreachable — no heartbeat ACK");
            onGatewayUnreachable.run();
        } else if (!neverAcked && !ackMissing && !gatewayReachable) {
            gatewayReachable = true;
            LOG.info("Gateway resumed");
            onGatewayResumed.run();
        }
    }

    /** Called by Controller when a heartbeat_ack packet is received from the gateway. */
    public void onAckReceived() {
        lastAckMs = System.currentTimeMillis();
    }

    public void stop() {
        if (scheduler != null) scheduler.shutdownNow();
    }

    public boolean isGatewayReachable() { return gatewayReachable; }
}
