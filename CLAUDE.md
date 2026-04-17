# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Last Updated: 2026-04-17

## Project Overview

MSE is a building-evacuation system. There are two implementations:

### Active implementation — Python (`NEW/`)

Laptop runs all pathfinding. Two ESP32s connect via USB serial.

1. **`NEW/mapnode20.py`** — simulation engine. 16-node topology, per-node stage cycling
   (NORMAL / MAYBE FIRE / FIRE), dual-path Dijkstra via networkx, interactive matplotlib map.
   - Click a node → cycles its stage (NORMAL → MAYBE FIRE → FIRE → NORMAL)
   - "Generate Scenario" button → one random node set to FIRE, rest randomly NORMAL or MAYBE FIRE
   - Warning banner reflects the selected node's own stage
2. **`NEW/node67_controller.py`** — hardware controller. Manages two ESP32s simultaneously:
   - **Sign ESP** (Node 7, `PORT_SIGN` default `/dev/ttyACM1`, `sketch_apr8a.ino`): listens for
     `"search"` button presses, sends `"left"`/`"right"` back, drives LED + buzzer
   - **Sensor ESP** (Node 6, `PORT_SENSOR` default `/dev/ttyACM0`, `sensor.ino`): reads
     `"temp,co2"` CSV and auto-sets Node 6's stage (NORMAL / MAYBE FIRE / FIRE); optional —
     if not connected, Node 6 stays simulated
   - `PUSH_INTERVAL = 5` constant at file top controls proactive push cadence (seconds)
   - Left panel: monospace table — columns: Node, Temp(°C), CO2(ppm), Time(s), Cost, Path
     (backup-path rows shown in blue text; BackupRoute column removed)
   - Top banner reflects Node 7's own stage (not global scenario)
   - SOS counter has a 2-second debounce to prevent button-bounce inflation
   - Countdown timer shown at bottom-right, fontsize 16
   - Scroll-wheel zoom on map axes; zoom persists across redraws
   - Legend uses circular `mlines.Line2D` markers for nodes and colored lines for edges/paths
   - Sensor-locked node (Node 6 when LIVE): manual click cycles direction but not stage
3. **`NEW/sketch_apr8a.ino`** — Sign ESP32 sketch (9600 baud). Button press → sends `"search"` to
   laptop. Receives `"left"`/`"right"` → blinks the corresponding LED + buzzer.
4. **`NEW/sensor.ino`** — Sensor ESP32 sketch reading DHT22 (temp, pin 4) and SGP30
   (CO2, SDA=18/SCL=19). Outputs `"Temp,CO2"` CSV at 9600 baud every 5 s; `"READ_ERROR"` on
   sensor failure.
   **Known issue:** `sgp.begin()` failure hangs the sketch in `while(1)` — check wiring if silent.

**Serial protocol (active):** plain text at 9600 baud.
- Sign ESP → laptop: `search`
- Laptop → Sign ESP: `left` or `right`
- Sensor ESP → laptop: `23.5,412` (temp°C, CO2 ppm CSV)

**Both ESPs use CH343 chip, VID:PID `1a86:55d3`.** Tell them apart by COM port number in
`usbipd list` / Device Manager — the one already shared from a prior session is the Sign ESP.

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

### Python hardware controller (both ESP32s connected)

**WSL only — attach both USBs first** (in Windows PowerShell as Admin):
```powershell
usbipd list                                # find BUSIDs (CH343, VID:PID 1a86:55d3)
usbipd bind --busid <BUSID-sign>           # one-time per device — survives reboots
usbipd bind --busid <BUSID-sensor>
usbipd attach --wsl --busid <BUSID-sign>   # each new session
usbipd attach --wsl --busid <BUSID-sensor>
```
Add user to dialout group if needed: `sudo usermod -a -G dialout $USER` (requires new terminal).

1. Upload `NEW/sketch_apr8a.ino` to the Sign ESP32 and `NEW/sensor.ino` to the Sensor ESP32.
2. Find ports after attaching both: `ls /dev/ttyUSB* /dev/ttyACM*`
3. Edit `PORT_SIGN` and `PORT_SENSOR` at the top of `node67_controller.py` if needed.
4. Run:

```bash
.venv/bin/python NEW/node67_controller.py
```

Sign ESP: button press → laptop computes direction → `"left"`/`"right"` → LED + buzzer.
Sensor ESP: optional; if connected, real readings auto-set Node 6's stage every 5 s.

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
│   ├── node67_controller.py         # Hardware controller — Sign ESP (node 7) + Sensor ESP (node 6)
│   ├── node7_controller.py          # Superseded single-ESP controller (kept for reference)
│   ├── sketch_apr8a.ino             # Sign ESP32 sketch (button + LED + buzzer, 9600 baud)
│   └── sensor.ino                   # Sensor ESP32 sketch (DHT22 + SGP30, CSV output, 9600 baud)
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
- On WSL, both ESP32 USBs require usbipd attach each session and `dialout` group membership.
- `node67_controller.py` direction logic for Node 7 is hardcoded: exit 15 → `"left"`, exit 16 →
  `"right"`. Generalising to other sign nodes requires extending this mapping.
- Both ESPs share VID:PID `1a86:55d3` (CH343). There is no programmatic way to tell them apart —
  rely on COM port numbers in `usbipd list` / Device Manager to identify which is which.
- `sensor.ino`: SGP30 expects `IAQmeasure()` called at 1 Hz for its baseline algorithm; current
  5-second interval may degrade accuracy. Also, `sgp.begin()` failure hangs in `while(1)` —
  consider a graceful fallback instead of halting.
- `node7_controller.py` is the superseded single-ESP controller and can be removed once
  `node67_controller.py` is confirmed stable in demo conditions.
