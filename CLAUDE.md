# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Last Updated: 2026-04-15

## Project Overview

MSE is a building-evacuation system. There are two implementations:

### Active implementation — Python (`NEW/`)

Laptop runs all pathfinding. ESP32 acts as a button + indicator only.

1. **`NEW/mapnode20.py`** — simulation engine. 16-node topology, per-node stage cycling
   (NORMAL / MAYBE FIRE / FIRE), dual-path Dijkstra via networkx, interactive matplotlib map.
   - Click a node → cycles its stage (NORMAL → MAYBE FIRE → FIRE → NORMAL)
   - "Generate Scenario" button → one random node set to FIRE, rest randomly NORMAL or MAYBE FIRE
   - Warning banner reflects the selected node's own stage
2. **`NEW/node7_controller.py`** — hardware controller. Listens on USB serial for `"search"` from
   the ESP32, runs the simulation for Node 7, sends `"left"` or `"right"` back, and redraws the map.
3. **`NEW/sketch_apr8a.ino`** — ESP32 sketch (9600 baud). Button press → sends `"search"` to
   laptop. Receives `"left"`/`"right"` → blinks the corresponding LED + buzzer.

**Serial protocol:** plain text at 9600 baud. ESP32 → laptop: `search`. Laptop → ESP32: `left` or `right`.

### Legacy Java app (`src/main/java/mse/`)

Earlier implementation. `EspController` reads `node_state` JSON from ESP32s, runs Dijkstra in
Java (`PathComputationService`), sends `path_push` JSON back. No longer the active plan but kept
for reference.

## Build & Run

### Python simulation (no hardware needed)

```bash
python3 -m venv .venv          # one-time
.venv/bin/pip install matplotlib networkx pyserial   # one-time
.venv/bin/python NEW/mapnode20.py
```

Opens a matplotlib window. Click any node to cycle its stage; press "Generate Scenario" to
randomise all nodes (one fire node, rest normal/maybe-fire).

### Python hardware controller (ESP32 connected)

**WSL only — attach USB first** (in Windows PowerShell as Admin):
```powershell
usbipd bind --busid <BUSID>
usbipd attach --wsl --busid <BUSID>
```
Find BUSID with `usbipd list` (look for CH340/CH343/CP210x).
Add user to dialout group if needed: `sudo usermod -a -G dialout $USER` (requires new terminal).

1. Upload `NEW/sketch_apr8a.ino` to the ESP32 via Arduino IDE.
2. Find the serial port: `ls /dev/ttyUSB* /dev/ttyACM*`
3. Edit `PORT` at the top of `node7_controller.py` to match (macOS port is commented out).
4. Run:

```bash
.venv/bin/python NEW/node7_controller.py
```

Press the button wired to pin 7 on the ESP32 → laptop computes evacuation direction for Node 7 →
sends `"left"` or `"right"` back → ESP32 blinks the corresponding LED + buzzer.

### Java app (legacy)

### Primary build (Maven fat jar)

```bash
mvn package -q
```

Output: `target/mse-controller-1.0-SNAPSHOT.jar`
The jar is self-contained (shade plugin bundles all dependencies).

### Run — laptop app (reads from Arduino Mega via USB serial)

```bash
java -jar target/mse-controller-1.0-SNAPSHOT.jar \
     <topology.json> <config.properties>
```

The manifest main class is `mse.controller.EspController`. If the configured serial port is absent,
the app still starts (serial bridge silently disabled).

### Run — topology tooling CLIs

```bash
# Validate a topology file (exits 0 on success, 1 on errors)
java -cp target/mse-controller-1.0-SNAPSHOT.jar \
     mse.topology.TopologyValidator sample-topology.json

# Interactive topology builder (prompts node/edge/save/quit)
java -cp target/mse-controller-1.0-SNAPSHOT.jar \
     mse.topology.TopologyGenerator

# Compile topology.json → topology.h (for Arduino Mega firmware)
java -cp target/mse-controller-1.0-SNAPSHOT.jar \
     mse.topology.TopologyCompiler sample-topology.json topology.h
```

## Architecture

### Package map (`src/main/java/mse/`)

| Package | Key classes | Purpose |
|---|---|---|
| `mse` | `Node`, `Exit`, `Graph`, `PathCandidate` | Core domain model and pathfinding |
| `mse.topology` | `TopologyLoader`, `TopologyValidator`, `TopologyGenerator`, `TopologyCompiler` | JSON topology I/O, CLI tools, and C header generation |
| `mse.controller` | `EspController`, `DashboardDataSource`, `NodeState` | Laptop entry point; reads USB serial from Mega |
| `mse.dashboard` | `SwingDashboard` | Swing desktop dashboard |
| `mse.distress` | `DistressHandler`, `DistressRecord` | In-memory distress event storage |

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

Dijkstra in `Node.java` uses `System.identityHashCode` stored in `float[]` PQ entries — this has
a float precision bug for large hash codes. `PathComputationService` fixes this by using `double[]`
instead and is used by `EspController` for all path computation.

### USB protocol (ESP32 ↔ Laptop, newline-delimited JSON)

| Packet type | Direction | Description |
|---|---|---|
| `node_state` | ESP32 → Laptop | Sensor readings (temp, co2) from a node |
| `distress` | ESP32 → Laptop | Distress signal raised at a node |
| `distress_ack` | Laptop → ESP32 | Acknowledges distress receipt |
| `path_push` | Laptop → ESP32 | Evacuation direction after Dijkstra |

**node_state** (sent by ESP32 periodically):
```json
{"type": "node_state", "node_id": "1B", "temp": 25.0, "co2": 0.08}
```

**path_push** (sent by laptop after each Dijkstra run):
```json
{"type": "path_push", "node_id": "1B", "direction": "right"}
```

Each ESP32 connects to the laptop via USB serial. Port-to-node mapping is learned dynamically:
the first `node_state` packet from a port associates that node ID with that port's writer.
Only wired (connected) nodes receive `path_push`; topology-only nodes are skipped.

### Dashboard

`SwingDashboard` opens a Swing JFrame automatically on laptop app start. It implements the
`DashboardDataSource` interface, which `EspController` drives on every `state_snapshot` received.
Requires a display server — on WSL, enable WSLg or run from Windows PowerShell.

### Distress handling

`DistressHandler` stores distress events in memory for display on the dashboard. It does **not**
send SMS or make external HTTP calls.

### ESP32 gateway sketches

`esp32_gateway/esp32_gateway.ino` — Tinkercad-compatible single-file sketch. No external
libraries. Reads `path_push` from Mega via UART (Serial at 115200 baud), matches own `MY_NODE_ID`,
and stores the current direction string.

`esp32_gateway/speaker_and_led1.ino` — Extended sketch for Node 5 (`MY_NODE_ID "5"`) with speaker
(pin 8) and dual LED output (LED_LEFT pin 13, LED_RIGHT pin 12). Also uses 115200 baud.
Uses `Serial.readStringUntil('\n')` so it works in Tinkercad's Serial Monitor, which does not
send a line terminator.

## Directory Structure

```
MSE/
├── pom.xml                          # Maven build (Java 17, shade plugin)
├── config.properties                # Runtime configuration
├── sample-topology.json             # 4-node sample building topology
├── sample-scenario.json             # Scripted demo scenario (fire spreading)
├── NEW/                             # Active Python implementation
│   ├── mapnode20.py                 # Simulation engine + interactive matplotlib GUI
│   ├── node7_controller.py          # Hardware controller for Node 7 (USB serial)
│   └── sketch_apr8a.ino             # ESP32 sketch (button + LED + buzzer, 9600 baud)
├── esp32_gateway/
│   ├── esp32_gateway.ino            # Tinkercad-compatible ESP32 sketch (legacy)
│   └── speaker_and_led1.ino        # Node 5 variant with speaker + LED (legacy)
├── mega/                            # Partial Mega firmware (legacy, unused)
│   ├── serial_laptop.h / serial_laptop.cpp
└── src/main/java/mse/
    ├── Node.java                    # Core domain model
    ├── Exit.java                    # Exit node subclass
    ├── Graph.java                   # Node registry + validation
    ├── PathCandidate.java           # Path + distance value object
    ├── controller/
    │   ├── EspController.java       # Entry point; reads USB serial from ESP32s (legacy)
    │   ├── PathComputationService.java  # Multi-source Dijkstra with double[] fix
    │   ├── DashboardDataSource.java # Interface decoupling dashboard from EspController
    │   └── NodeState.java           # Runtime state per mesh node
    ├── dashboard/
    │   └── SwingDashboard.java      # Swing desktop dashboard
    ├── distress/
    │   ├── DistressHandler.java     # In-memory distress event store
    │   └── DistressRecord.java      # Distress event value object
    └── topology/
        ├── TopologyLoader.java      # Parses topology.json into Graph
        ├── TopologyCompiler.java    # Generates topology.h for Arduino Mega firmware
        ├── TopologyValidator.java   # CLI validator (checks symmetry, references)
        └── TopologyGenerator.java  # Interactive CLI topology builder
```

## Configuration (`config.properties`)

| Key | Default | Description |
|---|---|---|
| `serial.port.1` | `/dev/ttyUSB0` | Serial port for first ESP32 node (USB) |
| `serial.port.2` | `/dev/ttyUSB1` | Serial port for second ESP32 node (USB) |

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
`TopologyCompiler` uses the `direction` field to generate the `DIRECTIONS` array in `topology.h`.

## Dependencies (Maven)

| Library | Version | Use |
|---|---|---|
| Gson | 2.10.1 | JSON serialization/deserialization |
| jSerialComm | 2.10.4 | Cross-platform USB serial communication |

Java target: 17.

## Known Issues / TODOs

- `SwingDashboard` (legacy Java) requires a display server. On WSL, enable WSLg (`wsl --update`
  in PowerShell) or run the jar from Windows PowerShell directly.
- On WSL, ESP32 USB requires usbipd attach each session and `dialout` group membership.
- `node7_controller.py` is hardcoded to Node 7. Generalising to other nodes requires extending
  the direction mapping logic (currently only exit 15 → `"left"`, else `"right"`).
