package mse.controller;

import com.fazecast.jSerialComm.SerialPort;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Manages USB serial communication with the gateway ESP32.
 * Protocol: newline-delimited JSON at 115200 baud.
 *
 * If the configured port is not found, SerialBridge logs a warning and
 * operates in disabled mode — send() calls are no-ops, no listener callbacks fire.
 * This allows the controller to run fully with the simulator when no hardware is present.
 */
public class SerialBridge {

    private static final Logger LOG = Logger.getLogger(SerialBridge.class.getName());
    private static final int BAUD_RATE = 115200;

    private final String portName;
    private final Consumer<JsonObject> onPacket;
    private SerialPort port;
    private PrintWriter writer;
    private Thread readerThread;
    private volatile boolean running = false;
    private boolean enabled = false;

    public SerialBridge(String portName, Consumer<JsonObject> onPacket) {
        this.portName = portName;
        this.onPacket = onPacket;
    }

    public void start() {
        port = SerialPort.getCommPort(portName);
        if (!port.openPort()) {
            LOG.warning("Serial port not found or could not be opened: " + portName
                + " — running in serial-disabled mode");
            return;
        }
        port.setBaudRate(BAUD_RATE);
        port.setComPortTimeouts(SerialPort.TIMEOUT_READ_BLOCKING, 0, 0);
        enabled = true;
        running = true;

        writer = new PrintWriter(new OutputStreamWriter(
            port.getOutputStream(), StandardCharsets.UTF_8), true);

        readerThread = new Thread(this::readLoop, "serial-reader");
        readerThread.setDaemon(true);
        readerThread.start();

        LOG.info("Serial bridge started on " + portName);
    }

    private void readLoop() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(port.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while (running && (line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                try {
                    JsonObject packet = JsonParser.parseString(line).getAsJsonObject();
                    onPacket.accept(packet);
                } catch (Exception e) {
                    LOG.warning("Malformed serial packet: " + line);
                }
            }
        } catch (IOException e) {
            if (running) LOG.warning("Serial read error: " + e.getMessage());
        }
    }

    /** Sends a JSON object as a single newline-terminated line. No-op if serial is disabled. */
    public void send(JsonObject packet) {
        if (!enabled || writer == null) return;
        writer.println(packet.toString());
    }

    public void stop() {
        running = false;
        if (port != null && port.isOpen()) port.closePort();
    }

    public boolean isEnabled() { return enabled; }
}
