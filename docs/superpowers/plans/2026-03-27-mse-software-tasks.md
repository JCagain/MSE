# MSE Software Track — Task Breakdown

## Scope (us only)
Topology tooling → Controller → Simulator (Normal mode) → Dashboard → Distress handling → Simulator fallback modes

## File Structure

```
PathFinder/
├── GraphGUI.java                        (unchanged — standalone GUI, do not touch)
├── pom.xml                              (new — Maven build)
└── src/main/java/mse/
    ├── Node.java                        (moved + add: package mse;)
    ├── Graph.java                       (moved + add: package mse;)
    ├── Exit.java                        (new — node subclass for exits)
    ├── topology/
    │   ├── TopologyLoader.java          (new — parses topology.json → Graph)
    │   ├── TopologyValidator.java       (new — CLI: checks bidirectionality)
    │   └── TopologyGenerator.java       (new — CLI: interactive topology builder)
    ├── controller/
    │   ├── Controller.java              (new — main app; wires all services)
    │   ├── NodeState.java               (new — controller's runtime view of one node)
    │   ├── PathComputationService.java  (new — multi-source Dijkstra + path push + debounce)
    │   ├── SerialBridge.java            (new — USB serial read/write with gateway)
    │   └── HeartbeatService.java        (new — periodic heartbeat + ack tracking)
    ├── simulator/
    │   ├── SimNode.java                 (new — virtual ESP32 node, Normal mode first)
    │   ├── Simulator.java               (new — manages N SimNodes, in-process delivery)
    │   └── ScenarioRunner.java          (new — scripted sensor-state change sequences)
    ├── dashboard/
    │   └── DashboardServer.java         (new — embedded Jetty; serves UI + SSE stream)
    └── distress/
        ├── DistressRecord.java          (new — value object for a distress event)
        └── DistressHandler.java         (new — SMS/HTTP POST/disk queue)
└── src/main/resources/
    └── dashboard.html                   (new — single-page dashboard UI)
PathFinder/sample-topology.json          (new — dev/test topology)
PathFinder/config.properties             (new — controller config)
```

---

## Task Order

### Task 1 — Maven setup
- Create `pom.xml` (deps: Gson, Jetty, jSerialComm, Twilio)
- Verify: `mvn compile` succeeds on empty src tree

### Task 2 — Migrate Node.java + Graph.java
- Move to `src/main/java/mse/`, add `package mse;`
- Verify: `mvn compile`

### Task 3 — Exit.java
- Minimal subclass of Node; marks exit nodes
- Verify: `mvn compile`

### Task 4 — sample-topology.json + TopologyLoader
- Write sample-topology.json (3 nodes: 1A, 1B, 1Exit-A matching spec examples)
- TopologyLoader parses topology.json → Graph (using Gson; two-pass: create nodes, then add edges)
- Verify: `mvn exec:java` prints loaded node count

### Task 5 — TopologyValidator CLI
- Checks every neighbor pair is symmetric (bidirectionality)
- Checks all referenced node_ids exist
- Prints OK or lists violations; exits non-zero on failure
- Verify: run against sample-topology.json (pass), then a broken one (fail)

### Task 6 — TopologyGenerator CLI
- Interactive prompt: add nodes, add edges, save to file
- Verify: generate a topology.json, validate it

### Task 7 — NodeState
- Plain data class: nodeId, macAddress, floor, locationLabel, isExit, distance, isPassable, sensorError, temperature, co2, nextHopId, topologyCrc32, lastSeenMs
- Verify: `mvn compile`

### Task 8 — PathComputationService
- Multi-source Dijkstra: all passable Exit nodes as sources (distance=0)
- Returns Map<String, PathResult> (nodeId → nextHopId + distance)
- Debounce: routine updates at most once per broadcastIntervalMs; route poison events (isPassable=false or distance=∞) bypass debounce and trigger immediately
- Verify: load sample-topology.json, set 1Exit-A passable, run → check next hops point toward exit

### Task 9 — SerialBridge
- Opens serial port via jSerialComm at 115200 baud
- Reads newline-delimited JSON lines; calls listener callback
- Writes JSON lines
- Gracefully handles port-not-found (logs warning, disables serial mode)
- Verify: compile; manual test with physical hardware later

### Task 10 — HeartbeatService
- Sends `{"type":"heartbeat","timestamp_ms":...}` every heartbeat.interval.ms via SerialBridge
- Tracks last heartbeat_ack time
- Calls onGatewayUnreachable() if no ack for heartbeat.interval.ms × 2
- Calls onGatewayResumed() when ack resumes
- Verify: `mvn compile`

### Task 11 — Controller
- Loads topology.json + config.properties on startup
- Validates HEARTBEAT_TIMEOUT_MS > 2 × heartbeat.interval.ms, fatal error if violated
- Starts SerialBridge, HeartbeatService
- Routes incoming packets by type: routing → update NodeState + trigger PathComputationService; heartbeat_ack → HeartbeatService; distress → DistressHandler
- On path computation result: sends path_push packets via SerialBridge
- Node timeout: if no routing packet within node.timeout.ms → mark unreachable, immediate Dijkstra rerun
- Verify: start with sample-topology.json, no serial port → logs "serial disabled", dashboard starts on port 8080

### Task 12 — SimNode + Simulator
- SimNode: holds sensor state; sends routing packet to Controller in-process every broadcastIntervalMs; receives path_push; Normal mode only
- Simulator: creates SimNodes from topology.json; delivers packets directly to Controller.handlePacket()
- Verify: start Controller + Simulator with sample-topology.json → all SimNodes appear on dashboard, routing packets logged

### Task 13 — ScenarioRunner
- Reads a scenario JSON file: list of `{delay_ms, node_id, temperature, co2}` steps
- Applies sensor changes to SimNodes at the given delays
- Verify: write a 3-step scenario (fire spreads from 1A), run it, observe routing updates in controller log

### Task 14 — DashboardServer + dashboard.html
- Embedded Jetty on port 8080 (configurable)
- `GET /` → serves dashboard.html
- `GET /api/state` → JSON array of all NodeState objects
- `GET /api/events` → SSE stream; Controller pushes an event on every state change
- `GET /api/distress` → JSON array of recent distress events
- dashboard.html: table of nodes (id, floor, passable, temp, co2, distance, next_hop, last_seen), distress alerts section, auto-updates via SSE
- Verify: open browser at localhost:8080 while Simulator is running → see live node table updating

### Task 15 — DistressRecord + DistressHandler
- DistressRecord: nodeId, seq, floor, locationLabel, timestampMs, receivedAtMs, ackSent
- DistressHandler: logs to disk (JSON lines), sends SMS via Twilio (skips if credentials absent), HTTP POST to configured endpoint, in-memory retry queue (persisted to disk on shutdown)
- Controller calls DistressHandler.handle(record), then sends distress_ack back via SerialBridge/Simulator
- Verify: trigger distress from a SimNode → appears in dashboard, logged to disk, ack received by SimNode

### Task 16 — Simulator fallback modes
- Add Mesh fallback to SimNode: when no controller heartbeat for HEARTBEAT_TIMEOUT_MS and gossip coverage ≥ 50%, run local multi-source Dijkstra using gossiped state
- Add Island fallback to SimNode: when no heartbeat AND coverage < 50%, use distance-vector (min over passable neighbors)
- Verify: kill Controller mid-run → SimNodes switch mode, continue routing; restart Controller → SimNodes return to Normal mode

---

## Run commands (after pom.xml exists)

```bash
cd PathFinder

# Compile
mvn compile

# Validate a topology file
mvn exec:java -Dexec.mainClass=mse.topology.TopologyValidator -Dexec.args="sample-topology.json"

# Generate a topology file interactively
mvn exec:java -Dexec.mainClass=mse.topology.TopologyGenerator

# Run controller + simulator
mvn exec:java -Dexec.mainClass=mse.controller.Controller -Dexec.args="sample-topology.json config.properties"
```

## Dependencies (pom.xml)
- `com.google.code.gson:gson:2.10.1` — JSON parsing
- `org.eclipse.jetty:jetty-server:11.0.18` — embedded HTTP
- `org.eclipse.jetty:jetty-servlet:11.0.18` — servlet support for SSE
- `com.fazecast:jSerialComm:2.10.4` — serial comms
- `com.twilio.sdk:twilio:9.14.0` — SMS (optional; skipped if credentials absent)
