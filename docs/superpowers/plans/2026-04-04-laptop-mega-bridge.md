# Laptop MegaBridge Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the Controller + Simulator stack with a thin `MegaBridge` that reads state snapshots from the Arduino Mega over USB serial and drives the existing `SwingDashboard`.

**Architecture:** Extract `DashboardDataSource` interface so `SwingDashboard` has no direct `Controller` dependency; `MegaBridge` opens USB serial with jSerialComm, parses `state_snapshot` and `distress` packets in a background thread, updates a `NodeState` map built from `topology.json` at startup, and calls `dashboard.push()` on each update. `TopologyCompiler` is a new CLI that generates `topology.h` from `topology.json` as a build artifact for the MEGA firmware.

**Tech Stack:** Java 17, jSerialComm 2.10.4, Gson 2.10.1, JUnit Jupiter 5.10.0, Maven

---

## File Map

| Action | Path | Responsibility |
|---|---|---|
| Create | `src/main/java/mse/controller/DashboardDataSource.java` | Interface with 3 read-only methods |
| Create | `src/main/java/mse/controller/MegaBridge.java` | Entry point, serial reader, state updater |
| Create | `src/main/java/mse/topology/TopologyCompiler.java` | CLI: topology.json → topology.h |
| Modify | `src/main/java/mse/distress/DistressHandler.java` | Simplify to in-memory list only |
| Modify | `src/main/java/mse/dashboard/SwingDashboard.java` | Use `DashboardDataSource` instead of `Controller` |
| Modify | `pom.xml` | Add JUnit 5, update manifest main class |
| Create | `src/test/java/mse/distress/DistressHandlerTest.java` | Unit tests |
| Create | `src/test/java/mse/topology/TopologyCompilerTest.java` | Unit tests |
| Create | `src/test/java/mse/controller/MegaBridgeTest.java` | Unit tests |
| Delete | `src/main/java/mse/controller/Controller.java` | Replaced by MegaBridge |
| Delete | `src/main/java/mse/controller/SerialBridge.java` | MEGA owns ESP32 serial |
| Delete | `src/main/java/mse/controller/HeartbeatService.java` | MEGA handles heartbeat |
| Delete | `src/main/java/mse/controller/PathComputationService.java` | MEGA runs Dijkstra |
| Delete | `src/main/java/mse/simulator/Simulator.java` | No longer applicable |
| Delete | `src/main/java/mse/simulator/SimNode.java` | No longer applicable |
| Delete | `src/main/java/mse/simulator/ScenarioRunner.java` | No longer applicable |

---

### Task 1: Add JUnit 5 to pom.xml

**Files:**
- Modify: `pom.xml`

- [ ] **Step 1: Add JUnit 5 dependency and Surefire plugin to pom.xml**

In `pom.xml`, add inside `<dependencies>`:
```xml
<dependency>
    <groupId>org.junit.jupiter</groupId>
    <artifactId>junit-jupiter</artifactId>
    <version>5.10.0</version>
    <scope>test</scope>
</dependency>
```

Add inside `<build><plugins>`:
```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-surefire-plugin</artifactId>
    <version>3.1.2</version>
</plugin>
```

- [ ] **Step 2: Verify Maven compiles cleanly**

Run: `mvn test-compile -q`
Expected: BUILD SUCCESS (no test classes yet — that's fine)

- [ ] **Step 3: Create test directory structure**

Run: `mkdir -p src/test/java/mse/controller src/test/java/mse/topology src/test/java/mse/distress`

- [ ] **Step 4: Commit**

```bash
git add pom.xml
git commit -m "build: add JUnit 5 and Surefire plugin"
```

---

### Task 2: DashboardDataSource Interface

**Files:**
- Create: `src/main/java/mse/controller/DashboardDataSource.java`

- [ ] **Step 1: Create the interface**

```java
package mse.controller;

import mse.Graph;
import mse.distress.DistressRecord;

import java.util.List;
import java.util.Map;

public interface DashboardDataSource {
    Map<String, NodeState> getNodeStates();
    Graph getGraph();
    List<DistressRecord> getRecentDistressEvents();
}
```

- [ ] **Step 2: Compile**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/mse/controller/DashboardDataSource.java
git commit -m "feat: add DashboardDataSource interface"
```

---

### Task 3: Simplify DistressHandler

**Files:**
- Modify: `src/main/java/mse/distress/DistressHandler.java`
- Create: `src/test/java/mse/distress/DistressHandlerTest.java`

- [ ] **Step 1: Write the failing tests first**

Create `src/test/java/mse/distress/DistressHandlerTest.java`:
```java
package mse.distress;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DistressHandlerTest {

    @Test
    void handle_addsToRecentEvents() {
        DistressHandler handler = new DistressHandler();
        handler.handle(new DistressRecord("1A", 1, 1, "Main Corridor", 0L));
        assertEquals(1, handler.getRecentEvents().size());
        assertEquals("1A", handler.getRecentEvents().get(0).nodeId);
    }

    @Test
    void handle_newestFirst() {
        DistressHandler handler = new DistressHandler();
        handler.handle(new DistressRecord("1A", 1, 1, "A", 0L));
        handler.handle(new DistressRecord("1B", 2, 1, "B", 0L));
        assertEquals("1B", handler.getRecentEvents().get(0).nodeId);
    }

    @Test
    void handle_capsAt100() {
        DistressHandler handler = new DistressHandler();
        for (int i = 0; i < 101; i++) {
            handler.handle(new DistressRecord("N" + i, i, 1, "loc", 0L));
        }
        assertEquals(100, handler.getRecentEvents().size());
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -pl . -Dtest=DistressHandlerTest -q 2>&1 | tail -5`
Expected: FAIL — current DistressHandler has incompatible constructor

- [ ] **Step 3: Replace DistressHandler with simplified version**

Replace the entire content of `src/main/java/mse/distress/DistressHandler.java`:
```java
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
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn test -Dtest=DistressHandlerTest -q`
Expected: BUILD SUCCESS, Tests run: 3, Failures: 0

- [ ] **Step 5: Commit**

```bash
git add src/main/java/mse/distress/DistressHandler.java \
        src/test/java/mse/distress/DistressHandlerTest.java
git commit -m "feat: simplify DistressHandler to in-memory list"
```

---

### Task 4: Update SwingDashboard

**Files:**
- Modify: `src/main/java/mse/dashboard/SwingDashboard.java`

- [ ] **Step 1: Replace `Controller` reference with `DashboardDataSource`**

In `SwingDashboard.java`, make these changes:

Replace line 68:
```java
// OLD:
private final Controller controller;
// NEW:
private final DashboardDataSource source;
```

Replace lines 78–79:
```java
// OLD:
public SwingDashboard(Controller controller) {
    this.controller = controller;
// NEW:
public SwingDashboard(DashboardDataSource source) {
    this.source = source;
```

Remove the import for `mse.controller.Controller`.

- [ ] **Step 2: Replace all `controller.` call sites with `source.`**

There are 5 occurrences. Replace each:
- `controller.getNodeStates()` → `source.getNodeStates()`
- `controller.getRecentDistressEvents()` → `source.getRecentDistressEvents()`
- `controller.getGraph()` (both occurrences) → `source.getGraph()`

- [ ] **Step 3: Compile (expect errors — Controller still references SwingDashboard)**

Run: `mvn compile -q 2>&1 | grep "error:" | head -10`
Expected: errors in `Controller.java` only (Controller still constructs `SwingDashboard(this)` — that's fine, Controller gets deleted in Task 7)

- [ ] **Step 4: Commit the SwingDashboard change now, delete Controller later**

```bash
git add src/main/java/mse/dashboard/SwingDashboard.java
git commit -m "refactor: SwingDashboard depends on DashboardDataSource interface"
```

---

### Task 5: Implement TopologyCompiler

**Files:**
- Create: `src/main/java/mse/topology/TopologyCompiler.java`
- Create: `src/test/java/mse/topology/TopologyCompilerTest.java`

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/mse/topology/TopologyCompilerTest.java`:
```java
package mse.topology;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class TopologyCompilerTest {

    private static String compile() throws IOException {
        TopologyLoader.LoadResult result = TopologyLoader.load(Path.of("sample-topology.json"));
        return TopologyCompiler.compile(result.nodeJsons);
    }

    @Test
    void compile_correctNodeCount() throws IOException {
        assertTrue(compile().contains("#define NUM_NODES 4"));
    }

    @Test
    void compile_correctMaxNeighbors() throws IOException {
        // Each node in sample-topology.json has exactly 2 neighbors
        assertTrue(compile().contains("#define MAX_NEIGHBORS 2"));
    }

    @Test
    void compile_nodeIdsInDeclarationOrder() throws IOException {
        assertTrue(compile().contains("\"1A\", \"1B\", \"1C\", \"1Exit-A\""));
    }

    @Test
    void compile_exitFlagCorrect() throws IOException {
        // Only 1Exit-A (index 3) is an exit
        assertTrue(compile().contains("IS_EXIT[NUM_NODES] = {false, false, false, true}"));
    }

    @Test
    void compile_neighborIndicesCorrect() throws IOException {
        String out = compile();
        // 1A (idx 0) → 1B (idx 1) and 1C (idx 2)
        assertTrue(out.contains("{1, 2}"), "1A neighbors: " + out);
        // 1Exit-A (idx 3) → 1B (idx 1) and 1C (idx 2)
        assertTrue(out.contains("{1, 2}"));
        // 1B (idx 1) → 1A (idx 0) and 1Exit-A (idx 3)
        assertTrue(out.contains("{0, 3}"));
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -Dtest=TopologyCompilerTest -q 2>&1 | tail -5`
Expected: FAIL — TopologyCompiler does not exist yet

- [ ] **Step 3: Implement TopologyCompiler**

Create `src/main/java/mse/topology/TopologyCompiler.java`:
```java
package mse.topology;

import mse.topology.TopologyLoader.NeighborJson;
import mse.topology.TopologyLoader.NodeJson;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * CLI tool: converts topology.json into a topology.h C header for the Arduino Mega firmware.
 *
 * Usage: TopologyCompiler <topology.json> <output.h>
 */
public class TopologyCompiler {

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("Usage: TopologyCompiler <topology.json> <output.h>");
            System.exit(1);
        }
        TopologyLoader.LoadResult result = TopologyLoader.load(Path.of(args[0]));
        String header = compile(result.nodeJsons);
        Files.writeString(Path.of(args[1]), header);
        System.out.println("Wrote " + args[1] + "  (" + result.nodeJsons.size() + " nodes)");
    }

    /** Converts a node list into the content of topology.h. Package-private for testing. */
    static String compile(List<NodeJson> nodes) {
        int n = nodes.size();

        // Build stable index map (preserves topology.json order)
        Map<String, Integer> idx = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) idx.put(nodes.get(i).nodeId, i);

        // MAX_NEIGHBORS = largest neighbor count across all nodes
        int maxN = 0;
        for (NodeJson node : nodes) {
            if (node.neighbors != null) maxN = Math.max(maxN, node.neighbors.size());
        }

        StringBuilder sb = new StringBuilder();
        sb.append("// Auto-generated by TopologyCompiler — do not edit by hand\n");
        sb.append("#pragma once\n");
        sb.append("#include <stdint.h>\n");
        sb.append("#include <stdbool.h>\n\n");
        sb.append("#define NUM_NODES ").append(n).append("\n");
        sb.append("#define MAX_NEIGHBORS ").append(maxN).append("\n\n");

        // NODE_IDS
        sb.append("static const char* const NODE_IDS[NUM_NODES] = {");
        for (int i = 0; i < n; i++) {
            sb.append('"').append(nodes.get(i).nodeId).append('"');
            if (i < n - 1) sb.append(", ");
        }
        sb.append("};\n");

        // IS_EXIT
        sb.append("static const bool IS_EXIT[NUM_NODES] = {");
        for (int i = 0; i < n; i++) {
            sb.append(nodes.get(i).isExit ? "true" : "false");
            if (i < n - 1) sb.append(", ");
        }
        sb.append("};\n\n");

        // NEIGHBOR_IDX
        sb.append("static const int8_t NEIGHBOR_IDX[NUM_NODES][MAX_NEIGHBORS] = {\n");
        for (int i = 0; i < n; i++) {
            List<NeighborJson> nbs = neighbors(nodes.get(i));
            sb.append("    {");
            for (int j = 0; j < maxN; j++) {
                sb.append(j < nbs.size() ? idx.get(nbs.get(j).nodeId) : -1);
                if (j < maxN - 1) sb.append(", ");
            }
            sb.append("}");
            if (i < n - 1) sb.append(",");
            sb.append("   // ").append(nodes.get(i).nodeId).append("\n");
        }
        sb.append("};\n\n");

        // WEIGHTS
        sb.append("static const float WEIGHTS[NUM_NODES][MAX_NEIGHBORS] = {\n");
        for (int i = 0; i < n; i++) {
            List<NeighborJson> nbs = neighbors(nodes.get(i));
            sb.append("    {");
            for (int j = 0; j < maxN; j++) {
                sb.append(j < nbs.size() ? nbs.get(j).edgeWeight + "f" : "0.0f");
                if (j < maxN - 1) sb.append(", ");
            }
            sb.append("}");
            if (i < n - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("};\n");

        return sb.toString();
    }

    private static List<NeighborJson> neighbors(NodeJson node) {
        return node.neighbors != null ? node.neighbors : Collections.emptyList();
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn test -Dtest=TopologyCompilerTest -q`
Expected: BUILD SUCCESS, Tests run: 5, Failures: 0

- [ ] **Step 5: Commit**

```bash
git add src/main/java/mse/topology/TopologyCompiler.java \
        src/test/java/mse/topology/TopologyCompilerTest.java
git commit -m "feat: add TopologyCompiler CLI (topology.json → topology.h)"
```

---

### Task 6: Implement MegaBridge

**Files:**
- Create: `src/main/java/mse/controller/MegaBridge.java`
- Create: `src/test/java/mse/controller/MegaBridgeTest.java`

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/mse/controller/MegaBridgeTest.java`:
```java
package mse.controller;

import mse.distress.DistressRecord;
import mse.topology.TopologyLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MegaBridgeTest {

    private MegaBridge bridge;

    @BeforeEach
    void setUp() throws IOException {
        TopologyLoader.LoadResult result = TopologyLoader.load(Path.of("sample-topology.json"));
        bridge = new MegaBridge(result.graph, result.nodeJsons, "/dev/null");
    }

    @Test
    void initialNodeStates_matchTopology() {
        assertEquals(4, bridge.getNodeStates().size());
        assertTrue(bridge.getNodeStates().containsKey("1A"));
        assertTrue(bridge.getNodeStates().get("1Exit-A").isExit);
    }

    @Test
    void handleSnapshot_updatesPassability() {
        bridge.handleLine("{\"type\":\"state_snapshot\",\"nodes\":[" +
            "{\"id\":\"1A\",\"passable\":false,\"temp\":80.0,\"co2\":0.5," +
            "\"next_hop\":null,\"dist\":3.4028235E38,\"distress\":false,\"timed_out\":false}]}");
        assertFalse(bridge.getNodeStates().get("1A").isPassable);
    }

    @Test
    void handleSnapshot_updatesSensorAndPath() {
        bridge.handleLine("{\"type\":\"state_snapshot\",\"nodes\":[" +
            "{\"id\":\"1A\",\"passable\":true,\"temp\":45.5,\"co2\":0.3," +
            "\"next_hop\":\"1C\",\"dist\":7.0,\"distress\":false,\"timed_out\":false}]}");
        NodeState s = bridge.getNodeStates().get("1A");
        assertEquals(45.5f, s.temperature,      0.01f);
        assertEquals(0.3f,  s.co2,              0.01f);
        assertEquals("1C",  s.computedNextHopId);
        assertEquals(7.0f,  s.computedDistance, 0.01f);
    }

    @Test
    void handleDistress_addsToRecentEvents() {
        bridge.handleLine("{\"type\":\"distress\",\"node_id\":\"1A\",\"seq\":3," +
            "\"floor\":1,\"location_label\":\"Main Corridor West\",\"timestamp_ms\":1712345678}");
        List<DistressRecord> events = bridge.getRecentDistressEvents();
        assertEquals(1, events.size());
        assertEquals("1A", events.get(0).nodeId);
        assertEquals(3,    events.get(0).seq);
    }

    @Test
    void unknownNodeInSnapshot_silentlyIgnored() {
        assertDoesNotThrow(() -> bridge.handleLine("{\"type\":\"state_snapshot\",\"nodes\":[" +
            "{\"id\":\"UNKNOWN\",\"passable\":true,\"temp\":20.0,\"co2\":0.1," +
            "\"next_hop\":null,\"dist\":0.0,\"distress\":false,\"timed_out\":false}]}"));
    }

    @Test
    void malformedJson_silentlyIgnored() {
        assertDoesNotThrow(() -> bridge.handleLine("not json at all"));
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -Dtest=MegaBridgeTest -q 2>&1 | tail -5`
Expected: FAIL — MegaBridge does not exist yet

- [ ] **Step 3: Implement MegaBridge**

Create `src/main/java/mse/controller/MegaBridge.java`:
```java
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
 *   state_snapshot → update NodeState map, dashboard.push()
 *   distress       → record event, send distress_ack, dashboard.push()
 */
public class MegaBridge implements DashboardDataSource {

    private static final Logger LOG = Logger.getLogger(MegaBridge.class.getName());
    private static final int BAUD_RATE = 115200;

    private final Graph graph;
    private final Map<String, NodeState> nodeStates;
    private final DistressHandler distressHandler = new DistressHandler();
    private final String portName;
    private final Gson gson = new Gson();

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
            LOG.warning("Cannot open " + portName + " — display-only mode");
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
        }
        if (dashboard != null) dashboard.push();
    }

    void handleDistress(JsonObject packet) {
        DistressRecord record = new DistressRecord(
            packet.get("node_id").getAsString(),
            packet.get("seq").getAsInt(),
            packet.has("floor")          ? packet.get("floor").getAsInt()               : 0,
            packet.has("location_label") ? packet.get("location_label").getAsString()   : "",
            packet.has("timestamp_ms")   ? packet.get("timestamp_ms").getAsLong()       : 0L);
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
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn test -Dtest=MegaBridgeTest -q`
Expected: BUILD SUCCESS, Tests run: 6, Failures: 0

- [ ] **Step 5: Commit**

```bash
git add src/main/java/mse/controller/MegaBridge.java \
        src/test/java/mse/controller/MegaBridgeTest.java
git commit -m "feat: implement MegaBridge — reads state snapshots from MEGA over USB serial"
```

---

### Task 7: Update Manifest, Delete Dead Code, Full Build

**Files:**
- Modify: `pom.xml`
- Delete: 7 source files

- [ ] **Step 1: Update pom.xml manifest main class**

In `pom.xml`, change the `<mainClass>` inside `maven-jar-plugin`:
```xml
<!-- OLD: -->
<mainClass>mse.controller.Controller</mainClass>
<!-- NEW: -->
<mainClass>mse.controller.MegaBridge</mainClass>
```

- [ ] **Step 2: Delete the seven dead source files**

```bash
rm src/main/java/mse/controller/Controller.java
rm src/main/java/mse/controller/SerialBridge.java
rm src/main/java/mse/controller/HeartbeatService.java
rm src/main/java/mse/controller/PathComputationService.java
rm src/main/java/mse/simulator/Simulator.java
rm src/main/java/mse/simulator/SimNode.java
rm src/main/java/mse/simulator/ScenarioRunner.java
```

- [ ] **Step 3: Full build and test run**

Run: `mvn package -q`
Expected: BUILD SUCCESS — fat jar rebuilt, all tests pass

- [ ] **Step 4: Smoke-test the topology compiler with sample topology**

Run:
```bash
java -cp target/mse-controller-1.0-SNAPSHOT.jar \
     mse.topology.TopologyCompiler sample-topology.json /tmp/topology.h && cat /tmp/topology.h
```
Expected: C header printed to stdout with 4 nodes, IS_EXIT correctly set for 1Exit-A.

- [ ] **Step 5: Commit**

```bash
git add pom.xml
git rm src/main/java/mse/controller/Controller.java \
       src/main/java/mse/controller/SerialBridge.java \
       src/main/java/mse/controller/HeartbeatService.java \
       src/main/java/mse/controller/PathComputationService.java \
       src/main/java/mse/simulator/Simulator.java \
       src/main/java/mse/simulator/SimNode.java \
       src/main/java/mse/simulator/ScenarioRunner.java
git commit -m "feat: complete laptop/MEGA split — remove Controller stack, wire MegaBridge"
```
