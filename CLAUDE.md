# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Last Updated: 2026-04-01

## Project Overview

MSE is an embedded building-evacuation system. ESP32 mesh nodes sense temperature and CO2,
report passability to a central controller over USB serial (newline-delimited JSON), and display
directional evacuation arrows on exit-light hardware. The controller computes shortest paths
(Dijkstra / Yen's K-Shortest Paths) and pushes `path_push` packets back to nodes.

## Build & Run

### Primary build (Maven fat jar)

```bash
mvn package -q
```

Output: `target/mse-controller-1.0-SNAPSHOT.jar`
The jar is self-contained (shade plugin bundles all dependencies).

### Run — simulator mode (no hardware required)

```bash
java -cp target/mse-controller-1.0-SNAPSHOT.jar \
     mse.simulator.Simulator \
     sample-topology.json config.properties
```

### Run — mixed mode (one real node + rest simulated)

Set `hardware.nodes=<node_id>` in `config.properties`. The simulator skips those node IDs so
real hardware packets and simulated packets don't duplicate each other at the controller.

### Run — hardware mode (ESP32 gateway on serial)

```bash
java -jar target/mse-controller-1.0-SNAPSHOT.jar \
     <topology.json> <config.properties>
```

The manifest main class is `mse.controller.Controller`. If the configured serial port is absent,
`SerialBridge` silently disables itself so the controller still runs.

### Run — topology tooling CLIs

```bash
# Validate a topology file (exits 0 on success, 1 on errors)
java -cp target/mse-controller-1.0-SNAPSHOT.jar \
     mse.topology.TopologyValidator sample-topology.json

# Interactive topology builder (prompts node/edge/save/quit)
java -cp target/mse-controller-1.0-SNAPSHOT.jar \
     mse.topology.TopologyGenerator
```

## Architecture

### Package map (`src/main/java/mse/`)

| Package | Key classes | Purpose |
|---|---|---|
| `mse` | `Node`, `Exit`, `Graph`, `PathCandidate` | Core domain model and pathfinding |
| `mse.topology` | `TopologyLoader`, `TopologyValidator`, `TopologyGenerator` | JSON topology I/O and CLI tools |
| `mse.controller` | `Controller`, `NodeState`, `SerialBridge`, `HeartbeatService`, `PathComputationService` | Runtime controller and serial comms |
| `mse.dashboard` | `SwingDashboard` | Swing desktop dashboard |
| `mse.distress` | `DistressHandler`, `DistressRecord` | Distress signal handling and notification |
| `mse.simulator` | `Simulator`, `SimNode`, `ScenarioRunner` | In-process hardware simulator |

### Core domain model (`mse`)

**`Node`**: Room/zone in a building. Passability is determined dynamically:
- Default: passable if `temperature <= temperatureThreshold` AND `gasConcentration <= gasConcentrationThreshold`
- `passableOverride` (boolean) takes precedence when set, bypassing sensor thresholds
- Default thresholds: 60°C temperature, 0.5 gas concentration

**`Exit extends Node`**: An evacuation exit node. Can be force-blocked via passable override
(e.g. structurally damaged exit).

**`PathCandidate`**: Value object holding a path (`List<Node>`) and total distance.
Implements `Comparable` for priority queues.

**`Graph`**: Registry of nodes by ID with a `validate()` method that checks every node has at
least one connection.

### Pathfinding algorithms (on `Node`)

- **`shortestPathTo(Node target)`** — Dijkstra; always allows reaching `target` regardless of its
  passable state, but skips other impassable intermediate nodes.
- **`findNearestExit()`** — Dijkstra variant searching for the nearest passable `Exit` instance.
- **`findKShortestPaths(Node target, int k)`** — Yen's K-Shortest Paths. Uses an internal
  `dijkstraWithExclusions()` helper for spur paths.

Dijkstra uses `System.identityHashCode` as the node identity key in the PQ (stored in a parallel
`hashToNode` map) because `float[]` arrays are used for PQ entries.

### Controller startup sequence

1. Load `topology.json` → build `Graph` + `NodeState` map
2. Validate heartbeat timing: `node.timeout.ms > 2 × heartbeat.interval.ms` (enforced on startup, fatal if violated)
3. Start `SerialBridge`, `HeartbeatService`, `PathComputationService`, `DistressHandler`
4. Start node-timeout watchdog thread

### Packet protocol (newline-delimited JSON over serial)

| Packet type | Direction | Description |
|---|---|---|
| `routing` | Node → Controller | Sensor readings + passability report |
| `heartbeat_ack` | Gateway → Controller | Confirms gateway is alive |
| `distress` | Node → Controller | Occupant pressed distress button |
| `path_push` | Controller → Node | Computed next-hop for evacuation routing |
| `distress_ack` | Controller → Node | Acknowledges distress receipt |

### Gateway node

The gateway ESP32 serves a dual role: serial bridge to the controller, and a full mesh node with
its own sensors and exit light. It must be listed in `topology.json` as a normal node. It sends
both `heartbeat_ack` (bridge role) and `routing` packets (node role), and receives `path_push`
like any other node.

### Mixed hardware + simulator

`hardware.nodes` in `config.properties` lists node IDs handled by real hardware
(comma-separated). `Simulator` skips creating `SimNode`s for those IDs, so the controller
receives exactly one stream of packets per node regardless of source.

### Simulator in-process wiring

```
SimNode → Simulator.toController() → Controller.handlePacket()
Controller.onPathResults() → Controller.simulatorSink → Simulator.fromController() → SimNode.receive()
```

Gossip: when a `SimNode` sends a `routing` packet, `Simulator` also delivers it to that node's
direct neighbors so they can update their gossip table (mesh fallback mode).

### Dashboard

`SwingDashboard` opens a Swing JFrame automatically on controller start. It updates on every path
recomputation. Requires a display server — on WSL, enable WSLg or run from Windows PowerShell.

### Distress handling

`DistressHandler` on receiving a `distress` packet:
1. Appends a JSON-Lines entry to `distress-log.jsonl`
2. Sends SMS via Twilio REST API (direct `HttpClient` POST — no Twilio SDK)
3. HTTP POSTs to `api.endpoint` (if configured)
4. Failed notifications enter a retry queue, persisted to `distress-retry-queue.json` across restarts

## Directory Structure

```
MSE/
├── pom.xml                          # Maven build (Java 17, shade plugin)
├── config.properties                # Runtime configuration
├── sample-topology.json             # 4-node sample building topology
├── sample-scenario.json             # Scripted demo scenario (fire spreading)
└── src/main/java/mse/
    ├── Node.java                    # Core domain model
    ├── Exit.java                    # Exit node subclass
    ├── Graph.java                   # Node registry + validation
    ├── PathCandidate.java           # Path + distance value object
    ├── controller/
    │   ├── Controller.java          # Main controller (entry point for hardware mode)
    │   ├── NodeState.java           # Runtime state per mesh node
    │   ├── SerialBridge.java        # USB serial <-> JSON bridge (jSerialComm)
    │   ├── HeartbeatService.java    # Periodic heartbeat + gateway reachability
    │   └── PathComputationService.java  # Scheduled Dijkstra + broadcast
    ├── dashboard/
    │   └── SwingDashboard.java      # Swing desktop dashboard
    ├── distress/
    │   ├── DistressHandler.java     # SMS/HTTP notification + retry queue
    │   └── DistressRecord.java      # Distress event value object
    ├── simulator/
    │   ├── Simulator.java           # In-process simulator (entry point for sim mode)
    │   ├── SimNode.java             # Virtual mesh node
    │   └── ScenarioRunner.java      # Scripted scenario replay
    └── topology/
        ├── TopologyLoader.java      # Parses topology.json into Graph
        ├── TopologyValidator.java   # CLI validator (checks symmetry, references)
        └── TopologyGenerator.java   # Interactive CLI topology builder
```

## Configuration (`config.properties`)

| Key | Default | Description |
|---|---|---|
| `serial.port` | `/dev/ttyUSB0` | Serial port for gateway ESP32 |
| `heartbeat.interval.ms` | `5000` | How often to send heartbeat pings |
| `node.timeout.ms` | `20000` | Node marked offline after this idle period (must be > 2x heartbeat) |
| `broadcast.interval.ms` | `3000` | Minimum interval between path-push broadcasts |
| `hardware.nodes` | _(blank)_ | Comma-separated node IDs on real hardware (simulator skips these) |
| `sms.recipients` | _(blank)_ | Comma-separated phone numbers for distress SMS |
| `api.endpoint` | _(blank)_ | HTTP POST endpoint for distress notifications |
| `api.timeout.ms` | `5000` | Timeout for external API calls |
| `controller.distress.notification.retry.interval.ms` | `10000` | Retry interval for failed distress notifications |
| `twilio.account.sid` | _(blank)_ | Twilio credentials (leave blank to disable SMS) |
| `twilio.auth.token` | _(blank)_ | Twilio credentials |
| `twilio.from.number` | _(blank)_ | Twilio sender number |
| `cellular.fallback.enabled` | `false` | Enable cellular fallback (not yet implemented) |
| `mesh.fallback.min.coverage.pct` | `50` | Min % of nodes needed before mesh fallback triggers |

## Topology JSON Format

Each node entry in `topology.json`:

```json
{
  "node_id": "1A",
  "mac_address": "AA:BB:CC:DD:EE:01",
  "floor": 1,
  "location_label": "Main Corridor West",
  "is_exit": false,
  "neighbors": [
    { "node_id": "1B", "edge_weight": 5.0, "direction": "right" }
  ]
}
```

Edges must be listed symmetrically (A lists B and B lists A with the same `edge_weight`).
`TopologyValidator` enforces this. `is_exit: true` nodes become `Exit` instances in the graph.

## Scenario JSON Format

```json
{
  "description": "Fire starts at 1A, spreads toward 1C",
  "steps": [
    { "delay_ms": 3000, "node_id": "1A", "temperature": 80.0, "co2": 0.1 }
  ]
}
```

`delay_ms` is relative to `ScenarioRunner.start()`, not cumulative. Steps are scheduled
concurrently via `ScheduledExecutorService`.

## Dependencies (Maven)

| Library | Version | Use |
|---|---|---|
| Gson | 2.10.1 | JSON serialization/deserialization |
| jSerialComm | 2.10.4 | Cross-platform USB serial communication |

Java target: 17.

## Known Issues / TODOs

- `cellular.fallback.enabled` is wired into config but the cellular fallback path is not yet implemented.
- `SwingDashboard` requires a display server. On WSL, enable WSLg (`wsl --update` in PowerShell)
  or run the jar from Windows PowerShell directly.
