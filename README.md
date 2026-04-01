# MSE — Emergency Exit System

A distributed building-evacuation system built on ESP32 mesh nodes and a controller.
Each node senses temperature and CO₂, reports to the controller over USB serial, and drives an
exit-light display showing the nearest safe escape route. The controller runs multi-source Dijkstra continuously and pushes updated next-hop instructions to every node.

```
[ESP32 node]──ESP-NOW──[ESP32 node]──ESP-NOW──[ESP32 gateway]──USB serial──[Controller]
     │                      │                        │                              │
  sensors               sensors                  sensors + bridge            path computation
  exit light            exit light               exit light                  desktop dashboard
  help button           help button              help button                 distress alerts
```

---

## Prerequisites

Verify: `java -version` and `mvn -version`

**Java 17+**
- macOS: `brew install openjdk@17`
- Windows: `winget install Microsoft.OpenJDK.17`
- Linux: `sudo apt install openjdk-17-jdk`

**Maven**
- macOS: `brew install maven`
- Windows: manually download from `https://maven.apache.org/download.cgi`
- Linux: `sudo apt install maven`


---

## Quick Start (no hardware required)

```bash
mvn package -q
java -cp target/mse-controller-1.0-SNAPSHOT.jar mse.simulator.Simulator sample-topology.json config.properties
```

A desktop window opens showing the live node table and distress alerts.

---

## Hardware Integration Guide

### What's done

Controller:
1. Initialzation: Loads a building topology (nodes, edges, exits) from a JSON file
2. Path finding:
- Tracks node passability from sensor readings (temp, CO₂)
- Computes a route for every node
- Pushes `path_push` packets to nodes telling which direction to point
3. Handles the distress button: logs the event, sends SMS (not for real yet), retries on failure
4. Shows a live desktop dashboard with the state of every node

Simulator: Acts as virtual ESP32 nodes. The simulator and real hardware can run side by side.

---

### TODO

1. Write JSON based on real building planes
2. The ESP32 gateway node:
A. **Read sensors** — temperature and CO₂ at regular intervals
B. **Send `routing` packets** over USB serial to report its state
C. **Respond to `heartbeat`** pings from the controller with a `heartbeat_ack`
D. **Send `distress` packets** when the help button is pressed
E. **Receive `path_push` packets** and drive the exit light in the indicated direction
F. **Bridge the mesh** — forward packets between the ESP-NOW mesh and the USB serial link (for future nodes)

---

### Serial protocol

All communication is **newline-delimited JSON at 115200 baud** over USB serial. One JSON object per line.

**You send → controller:**

```json
// Periodic sensor report (send every few seconds)
{
  "type": "routing",
  "node_id": "1A",
  "is_passable": true,
  "temperature": 24.5,
  "co2": 0.12,
  "sensor_error": false
}

// Reply to every heartbeat ping
{"type": "heartbeat_ack"}

// When help button is pressed
{
  "type": "distress",
  "node_id": "1A",
  "seq": 1,
  "floor": 1,
  "location_label": "Main Corridor West",
  "timestamp_ms": 1234567890
}
```

`seq` should increment by 1 on each button press so the controller can detect duplicates.

**Controller sends → you:**

```json
// Periodic liveness check — always reply with heartbeat_ack
{"type": "heartbeat", "timestamp_ms": 1234567890}

// Your next-hop direction — point the exit arrow here
{"type": "path_push", "node_id": "1A", "next_hop_id": "1B", "path_distance": 7.5}

// Acknowledgement of your distress button press
{"type": "distress_ack", "node_id": "1A", "seq": 1, "timestamp_ms": 1234567890}
```

---

### Wiring into the current structure

**Step 1: Construct topology**
Write `main-lib.json` that represents nodes.
1. In project root, run 
   `java -cp target/mse-controller-1.0-SNAPSHOT.jar mse.topology.TopologyGenerator`
   to start topology generator.
2. Enter `main-lib.json`.
3. Enter `node`, add all nodes with id, floor, location label, and whether it's an exit or not.
4. Enter `edge`, add all edges with endnodes, weights (distances), and direction.
5. Run
   `java -cp target/mse-controller-1.0-SNAPSHOT.jar mse.topology.TopologyValidator main-lib.json`
   to see if the topology is valid. Exits 0 if clean. Will catch asymmetric edges, missing references, duplicate IDs.
You may stop/restart the generator anytime. Successfully written nodes and edges are auto saved.

Alternative approach: Manually editing with reference to `sample-topology.json`.


**Step 2: Add a real node to topology**

Select a node (preferably an intersection) in `main-lib.json`, set the real MAC address, and add the `node_id` to `hardware.nodes` at the end of `config.properties`.


**Step 3: Configure `config.properties`**

1. Serial port
- Linux/macOS — run `ls /dev/tty.* (macOS)` or `ls /dev/ttyUSB* /dev/ttyACM*` (Linux) before and after plugging in the ESP32; the new entry is your port
- Windows — Device Manager → Ports (COM & LPT)
Then set it in config.properties, e.g.
`serial.port=/dev/tty.usbserial-1410`

2. Passibility thresholds
   Change the settings:
   ```json
   passability.temperature.threshold=60.0
   passability.gas.threshold=0.5
   ```


**Step 4: Run in mixed mode**

```bash
java -cp target/mse-controller-1.0-SNAPSHOT.jar mse.simulator.Simulator main-lib.json config.properties
```
The physical node will talk to the controller over serial. All other nodes in the topology run as simulator instances. The dashboard will show both real and simulated nodes together.




---

## How It Works

### Routing

The controller loads a `topology.json` file describing the building graph (rooms, corridors, exits,
edge weights). It runs **multi-source Dijkstra** seeded from all passable exit nodes, producing a
next-hop and distance for every room. Results are pushed to nodes as `path_push` packets. Each
node's exit light points in the direction of its assigned next hop.

When a node reports high temperature or CO₂, the controller marks it impassable, reruns Dijkstra
immediately (bypassing the normal debounce), and pushes updated routes.

### Fallback modes

If a node stops receiving `path_push` packets from the controller for 15 seconds, it switches to
local routing using gossip from its direct neighbors:

- **Mesh fallback** — enough neighbors are reachable; runs distance-vector over gossip.
- **Island fallback** — heavily fragmented mesh; uses whatever neighbors are still visible.

### Distress button

Pressing the help button on a node sends a `distress` packet to the controller, which:
1. Logs the event to `distress-log.jsonl`
2. Sends an SMS alert via Twilio REST API (if credentials configured)
3. HTTP POSTs to an external endpoint (if configured)
4. Retries failed notifications automatically; persists the retry queue across restarts

---

## Running Modes

> **WSL users:** the Swing window requires a display server. Enable WSLg (`wsl --update` in
> PowerShell, then restart WSL), or run the jar from Windows PowerShell directly pointing at the
> jar under `\\wsl$\Ubuntu\home\...\target\`.

### Simulator mode (development / demo)

Runs the full controller and a set of virtual ESP32 nodes in one process:

```bash
java -cp target/mse-controller-1.0-SNAPSHOT.jar \
     mse.simulator.Simulator \
     sample-topology.json config.properties
```

### Mixed mode (one real node + rest simulated)

Set `hardware.nodes` in `config.properties` to the real node's ID. The simulator skips that node
so the controller receives real packets from hardware and simulated packets for everything else:

```properties
hardware.nodes=1A
serial.port=/dev/ttyUSB0
```

### Hardware mode (all real nodes)

```bash
java -jar target/mse-controller-1.0-SNAPSHOT.jar \
     topology.json config.properties
```

If the serial port is absent, the serial bridge disables itself gracefully so the rest of the
system still runs.

### Fire scenario demo

The included `sample-scenario.json` simulates fire spreading from node `1A` to `1C`. Wire it into
`Simulator.main()` or run a `ScenarioRunner` against a live simulator:

```java
ScenarioRunner runner = new ScenarioRunner(simulator, Path.of("sample-scenario.json"));
runner.start();
```

---

## Gateway Node

The gateway ESP32 is both the serial bridge **and** a full mesh node. It should:
- Send `heartbeat_ack` in response to every `heartbeat` from the controller
- Send `routing` packets with its own sensor readings like any other node
- Receive `path_push` packets and drive its own exit light

Add the gateway to `topology.json` as a normal node entry.

---

## Serial Packet Protocol

All packets are newline-delimited JSON over USB serial.

### Node → Controller

**`routing`** — periodic sensor report
```json
{
  "type": "routing",
  "node_id": "1A",
  "is_passable": true,
  "temperature": 24.5,
  "co2": 0.12,
  "distance": 7.5,
  "sensor_error": false,
  "topology_crc32": "a1b2c3d4"
}
```

**`heartbeat_ack`** — gateway replies to every heartbeat ping
```json
{"type":"heartbeat_ack"}
```

**`distress`** — occupant pressed help button
```json
{
  "type": "distress",
  "node_id": "1A",
  "seq": 3,
  "floor": 1,
  "location_label": "Main Corridor West",
  "timestamp_ms": 1234567890
}
```

### Controller → Node

**`heartbeat`** — sent every `heartbeat.interval.ms`
```json
{"type":"heartbeat","timestamp_ms":1234567890}
```

**`path_push`** — computed next hop; node points exit arrow toward `next_hop_id`
```json
{"type":"path_push","node_id":"1A","next_hop_id":"1B","path_distance":7.5}
```

**`distress_ack`** — acknowledges a distress button press
```json
{"type":"distress_ack","node_id":"1A","seq":3,"timestamp_ms":1234567890}
```

---

## Topology File

`topology.json` describes the building graph. Edges must be listed symmetrically (both directions,
same weight). Use `TopologyValidator` to check a file before deploying.

```json
{
  "nodes": [
    {
      "node_id": "1A",
      "mac_address": "AA:BB:CC:DD:EE:01",
      "floor": 1,
      "location_label": "Main Corridor West",
      "is_exit": false,
      "neighbors": [
        { "node_id": "1B", "edge_weight": 5.0, "direction": "right" },
        { "node_id": "1C", "edge_weight": 3.0, "direction": "forward" }
      ]
    },
    {
      "node_id": "1Exit-A",
      "mac_address": "AA:BB:CC:DD:EE:04",
      "floor": 1,
      "location_label": "South Exit",
      "is_exit": true,
      "neighbors": [
        { "node_id": "1C", "edge_weight": 4.0, "direction": "left" }
      ]
    }
  ]
}
```

Node ID convention: `<floor><letter>` for regular nodes (e.g. `1A`, `2B`), `<floor>Exit-<letter>`
for exits (e.g. `1Exit-A`).

### Topology tools

```bash
# Validate symmetry, references, duplicate IDs
java -cp target/mse-controller-1.0-SNAPSHOT.jar \
     mse.topology.TopologyValidator topology.json

# Interactive builder (node / edge / list / save / quit)
java -cp target/mse-controller-1.0-SNAPSHOT.jar \
     mse.topology.TopologyGenerator
```

---

## Scenario File

Steps are scheduled relative to `ScenarioRunner.start()` (not cumulative):

```json
{
  "description": "Fire starts at 1A, spreads toward 1C",
  "steps": [
    { "delay_ms": 3000,  "node_id": "1A", "temperature": 80.0, "co2": 0.10 },
    { "delay_ms": 6000,  "node_id": "1C", "temperature": 70.0, "co2": 0.60 },
    { "delay_ms": 10000, "node_id": "1A", "temperature": 25.0, "co2": 0.10 },
    { "delay_ms": 13000, "node_id": "1C", "temperature": 25.0, "co2": 0.10 }
  ]
}
```

---

## Dashboard

A Swing desktop window opens automatically when the system starts. It updates live on every path
recomputation.

| Column | Description |
|---|---|
| Node | Node ID; exit nodes marked with a door icon |
| Floor | Building floor |
| Location | Human-readable label |
| Passable | Green YES / red NO based on sensor thresholds (60°C, CO₂ 0.5) |
| Temp / CO₂ | Current sensor readings |
| Next Hop | ID of the next node toward the nearest exit |
| Distance | Computed distance to nearest exit |
| Last Seen | Time since last routing packet |

Impassable nodes are highlighted red. Active distress alerts appear in the panel below the table.

---

## Configuration (`config.properties`)

| Key | Default | Description |
|---|---|---|
| `serial.port` | `/dev/ttyUSB0` | Gateway serial device |
| `heartbeat.interval.ms` | `5000` | Heartbeat ping interval |
| `node.timeout.ms` | `20000` | Node marked offline after this idle period (must be > 2× heartbeat) |
| `broadcast.interval.ms` | `3000` | Minimum interval between Dijkstra reruns |
| `hardware.nodes` | _(blank)_ | Comma-separated node IDs handled by real hardware (simulator skips these) |
| `sms.recipients` | _(blank)_ | Comma-separated phone numbers for distress SMS |
| `api.endpoint` | _(blank)_ | HTTP POST URL for distress notifications |
| `api.timeout.ms` | `5000` | External API call timeout |
| `controller.distress.notification.retry.interval.ms` | `10000` | Retry interval for failed notifications |
| `twilio.account.sid` | _(blank)_ | Twilio credentials (leave blank to disable SMS) |
| `twilio.auth.token` | _(blank)_ | Twilio credentials |
| `twilio.from.number` | _(blank)_ | Twilio sender number |
| `mesh.fallback.min.coverage.pct` | `50` | Gossip coverage % needed to switch to mesh fallback |

---

## Project Structure

```
MSE/
├── pom.xml                      # Maven build (Java 17, fat jar via shade plugin)
├── config.properties            # Runtime configuration
├── sample-topology.json         # 4-node sample building
├── sample-scenario.json         # Fire demo scenario
└── src/main/java/mse/
    ├── Node.java / Exit.java / Graph.java / PathCandidate.java   # Core domain
    ├── controller/              # Controller, serial bridge, heartbeat, path computation
    ├── dashboard/               # Swing desktop dashboard
    ├── distress/                # Distress event handling, SMS/HTTP notification, retry queue
    ├── simulator/               # In-process ESP32 simulator + scenario runner
    └── topology/                # Topology loader, validator, interactive generator
```

---

## Dependencies

| Library | Version |
|---|---|
| Gson | 2.10.1 |
| jSerialComm | 2.10.4 |

Java 17 required.
