# MSE — Emergency Exit System

A distributed building-evacuation system built on ESP32 mesh nodes and a Raspberry Pi controller.
Each node senses temperature and CO₂, reports to the controller over USB serial, and drives an
exit-light display showing the nearest safe escape route. The controller runs multi-source Dijkstra
continuously and pushes updated next-hop instructions to every node.

```
[ESP32 node]──ESP-NOW──[ESP32 node]──ESP-NOW──[ESP32 gateway]──USB serial──[Raspberry Pi controller]
     │                      │                        │                              │
  sensors               sensors                  sensors + bridge            path computation
  exit light            exit light               exit light                  desktop dashboard
  help button           help button              help button                 distress alerts
```

---

## Quick Start (no hardware required)

```bash
mvn package -q
java -cp target/mse-controller-1.0-SNAPSHOT.jar \
     mse.simulator.Simulator \
     sample-topology.json config.properties
```

A desktop window opens showing the live node table and distress alerts.

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
