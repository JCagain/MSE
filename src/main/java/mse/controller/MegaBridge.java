package mse.controller;

import com.fazecast.jSerialComm.SerialPort;
import com.google.gson.*;
import mse.Graph;
import mse.dashboard.SwingDashboard;
import mse.distress.DistressHandler;
import mse.distress.DistressRecord;
import mse.topology.TopologyLoader;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;

/**
 * Laptop-side entry point.
 *
 * Opens USB serial to the Arduino Mega, reads newline-JSON packets in a
 * background thread, and drives SwingDashboard:
 *   state_snapshot -> update NodeState map, dashboard.push()
 *   distress       -> record event, send distress_ack, dashboard.push()
 */
public class MegaBridge implements DashboardDataSource {

    private static final Logger LOG = Logger.getLogger(MegaBridge.class.getName());
    private static final int BAUD_RATE = 115200;

    private final Graph graph;
    private final Map<String, NodeState> nodeStates;
    private final DistressHandler distressHandler = new DistressHandler();
    private final String portName;

    private SwingDashboard dashboard;
    private PrintWriter writer;
    private volatile boolean running;

    // --- Entry point ---

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: MegaBridge <topology.json> <config.properties>");
            System.exit(1);
        }
        Properties config = new Properties();
        try (InputStream in = new FileInputStream(args[1])) { config.load(in); }
        TopologyLoader.LoadResult loaded = TopologyLoader.load(Path.of(args[0]), config);
        MegaBridge bridge = new MegaBridge(
            loaded.graph, loaded.nodeJsons,
            config.getProperty("serial.port", "/dev/ttyUSB0"));
        bridge.start();
        Thread.currentThread().join();
    }

    // --- Constructor (no I/O — safe for tests) ---

    public MegaBridge(Graph graph, List<TopologyLoader.NodeJson> nodeJsons, String portName) {
        this.graph    = graph;
        this.portName = portName;
        this.nodeStates = new LinkedHashMap<>();
        for (TopologyLoader.NodeJson nj : nodeJsons) {
            nodeStates.put(nj.nodeId, new NodeState(
                nj.nodeId, nj.macAddress, nj.floor, nj.locationLabel, nj.isExit));
        }
    }

    // --- Startup ---

    public void start() {
        SerialPort port = SerialPort.getCommPort(portName);
        if (port.openPort()) {
            port.setBaudRate(BAUD_RATE);
            port.setComPortTimeouts(SerialPort.TIMEOUT_READ_BLOCKING, 0, 0);
            writer = new PrintWriter(new OutputStreamWriter(
                port.getOutputStream(), StandardCharsets.UTF_8), true);
            running = true;
            Thread reader = new Thread(() -> readLoop(port), "mega-reader");
            reader.setDaemon(true);
            reader.start();
            LOG.info("MegaBridge connected on " + portName);
        } else {
            LOG.warning("Cannot open " + portName + " -- display-only mode");
        }
        dashboard = new SwingDashboard(this);
        dashboard.start();
    }

    // --- Serial reader ---

    private void readLoop(SerialPort port) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(port.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while (running && (line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty()) handleLine(line);
            }
        } catch (IOException e) {
            if (running) LOG.warning("Serial read error: " + e.getMessage());
        }
    }

    // --- Packet dispatch (package-private for testing) ---

    void handleLine(String line) {
        try {
            JsonObject packet = JsonParser.parseString(line).getAsJsonObject();
            switch (packet.has("type") ? packet.get("type").getAsString() : "") {
                case "state_snapshot" -> handleSnapshot(packet);
                case "distress"       -> handleDistress(packet);
                default               -> LOG.fine("Unknown packet type in: " + line);
            }
        } catch (Exception e) {
            LOG.warning("Malformed packet: " + line);
        }
    }

    void handleSnapshot(JsonObject packet) {
        for (JsonElement el : packet.getAsJsonArray("nodes")) {
            JsonObject n = el.getAsJsonObject();
            NodeState state = nodeStates.get(n.get("id").getAsString());
            if (state == null) continue;
            state.isPassable        = n.get("passable").getAsBoolean();
            state.temperature       = n.get("temp").getAsFloat();
            state.co2               = n.get("co2").getAsFloat();
            state.computedNextHopId = (!n.has("next_hop") || n.get("next_hop").isJsonNull())
                ? null : n.get("next_hop").getAsString();
            state.computedDistance  = n.get("dist").getAsFloat();
            state.timedOut          = n.get("timed_out").getAsBoolean();
            state.distressActive    = n.get("distress").getAsBoolean();
            state.lastSeenMs        = System.currentTimeMillis();
        }
        if (dashboard != null) dashboard.push();
    }

    void handleDistress(JsonObject packet) {
        DistressRecord record = new DistressRecord(
            packet.get("node_id").getAsString(),
            packet.get("seq").getAsInt(),
            packet.has("floor")          ? packet.get("floor").getAsInt()             : 0,
            packet.has("location_label") ? packet.get("location_label").getAsString() : "",
            packet.has("timestamp_ms")   ? packet.get("timestamp_ms").getAsLong()     : 0L);
        distressHandler.handle(record);
        sendDistressAck(record);
        if (dashboard != null) dashboard.push();
    }

    private void sendDistressAck(DistressRecord record) {
        if (writer == null) return;
        JsonObject ack = new JsonObject();
        ack.addProperty("type",         "distress_ack");
        ack.addProperty("node_id",      record.nodeId);
        ack.addProperty("seq",          record.seq);
        ack.addProperty("timestamp_ms", System.currentTimeMillis());
        writer.println(ack);
    }

    // --- DashboardDataSource ---

    @Override public Map<String, NodeState> getNodeStates() {
        return Collections.unmodifiableMap(nodeStates);
    }
    @Override public Graph getGraph()  { return graph; }
    @Override public List<DistressRecord> getRecentDistressEvents() {
        return distressHandler.getRecentEvents();
    }
}
