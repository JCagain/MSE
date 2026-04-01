# Emergency Exit Light System — Software Design Spec

**Date:** 2026-03-25 (revised 2026-03-26)
**Scope:** Software (controller, ESP32 firmware, simulator)
**Team:** Course project — 1–2 physical hardware prototypes; remaining nodes simulated

---

## 1. Overview

A smart emergency exit light system where each light is an ESP32 node that senses environmental conditions, participates in a distributed routing mesh, and directs occupants toward the nearest safe exit. A central controller (laptop or Raspberry Pi) optimizes routing and handles external emergency notifications.

**Exit nodes** are a special subset of nodes located at physical building exits. They always hold `distance = 0` and seed the routing wavefront. All other nodes compute their distance to the nearest reachable exit node.

The system degrades gracefully through three operating modes:

| Mode | Trigger | Who computes paths |
|---|---|---|
| **Normal** | Controller heartbeats received | Controller runs multi-source Dijkstra from exit nodes, pushes next-hop to each node |
| **Mesh fallback** | No controller heartbeat for `HEARTBEAT_TIMEOUT_MS`; node has gossiped state covering ≥ `MESH_FALLBACK_MIN_COVERAGE_PCT`% of topology nodes | Each ESP32 reconstructs full graph from gossiped state and runs multi-source Dijkstra locally |
| **Island fallback** | No controller heartbeat AND gossiped coverage < `MESH_FALLBACK_MIN_COVERAGE_PCT`% | Distance-vector: `distance = min(neighbor.distance + edge_weight)` over passable neighbors |

Mode transitions are automatic. A node re-enters Normal mode immediately on receiving a controller heartbeat.

---

## 2. System Architecture

```
[ Controller (laptop / Raspberry Pi 4 + UPS) ]
        |  (USB serial, 115200 baud, newline-delimited JSON)
[ Gateway ESP32 ]  ← full routing participant + serial bridge
        |
   (ESP-NOW peer-to-peer mesh, no router required)
        |
[ ESP32 Node ] ── [ ESP32 Node ] ── [ ESP32 Exit Node ]
[ ESP32 Node ] ── [ ESP32 Exit Node ]
```

**ESP-NOW** is the transport between all nodes. It is peer-to-peer, requires no WiFi router, and operates at the MAC layer — independent of building network.

**Gateway ESP32** runs the same firmware as all other nodes and is a full routing participant. When the controller is reachable it also bridges the mesh to the controller over USB serial. The gateway monitors the serial link: if no data is received from the controller within `HEARTBEAT_TIMEOUT_MS`, it stops attempting serial forwarding and operates as a pure mesh node until the serial link resumes.

**Node identity**: on boot, each ESP32 reads its own MAC address and matches it against the `mac_address` field in `topology.json` to determine its node ID, floor, location label, and neighbor list. All nodes run identical firmware — identity comes entirely from `topology.json`.

**Building topology** is stored in a static `topology.json`, pre-loaded onto each ESP32 file system (SPIFFS/LittleFS) and on the controller at deployment. Topology (edges, weights, directions) is static; only sensor state is dynamic.

---

## 3. topology.json Schema

`topology.json` is the single source of truth for the building graph. It is always pre-loaded at flash time and cannot be updated remotely (OTA update is out of scope).

**Edges are always bidirectional.** If node X lists node Y as a neighbor, node Y must also list node X in its own `neighbors` array with the appropriate `direction` from Y's perspective. A topology validator must reject any `topology.json` where this symmetry is violated.

Sample structure:

```json
{
  "nodes": [
    {
      "node_id": "A1",
      "mac_address": "AA:BB:CC:DD:EE:01",
      "floor": 1,
      "location_label": "Main Corridor West",
      "is_exit": false,
      "neighbors": [
        { "node_id": "A2", "edge_weight": 5.0, "direction": "right" },
        { "node_id": "B1", "edge_weight": 3.0, "direction": "forward" }
      ]
    },
    {
      "node_id": "A2",
      "mac_address": "AA:BB:CC:DD:EE:02",
      "floor": 1,
      "location_label": "Main Corridor East",
      "is_exit": false,
      "neighbors": [
        { "node_id": "A1", "edge_weight": 5.0, "direction": "left" },
        { "node_id": "EXIT-S", "edge_weight": 7.0, "direction": "forward" }
      ]
    },
    {
      "node_id": "EXIT-S",
      "mac_address": "AA:BB:CC:DD:EE:FF",
      "floor": 1,
      "location_label": "South Gate",
      "is_exit": true,
      "neighbors": [
        { "node_id": "A2", "edge_weight": 7.0, "direction": "back" }
      ]
    }
  ]
}
```

The `direction` field maps the neighbor relationship to a physical arrow direction (left / right / forward / back / up / down). The firmware reads this field to set the directional sign when `best_next_hop` is determined.

`MAX_DISTANCE` is computed at startup as the sum of all `edge_weight` values across all edges in `topology.json`. It is not hardcoded.

At startup, each node logs its `topology.json` CRC32 checksum in its first broadcast packet. The controller compares checksums across all nodes and flags any mismatch on the dashboard.

---

## 4. ESP32 Node Software

Each node runs a continuous loop across four responsibilities.

### 4.1 Sensing

- Reads temperature and CO2 every `SENSOR_POLL_INTERVAL_MS`
- If `temperature > TEMP_THRESHOLD` or `co2 > CO2_THRESHOLD`, node marks itself impassable
- If sensor returns out-of-range value, stale reading, or a bus-level hardware fault (I2C/SPI timeout, no response): treated identically — node marks itself impassable and flags `SENSOR_ERROR` in broadcast
- On becoming impassable, immediately broadcasts `distance = ∞` — active warning, not silent failure

### 4.2 Routing

Each node maintains:
- **Neighbor table** (in memory): `neighbor_id → { distance, edge_weight, direction, is_passable, last_seen_ms }`
- **Gossiped state table** (in memory): `node_id → { distance, is_passable, last_seen_ms }` — populated from all received routing broadcasts, not just immediate neighbors

#### Mode selection (evaluated after each received packet)

**Normal mode** — controller heartbeat received within `HEARTBEAT_TIMEOUT_MS`. Node accepts path-push packets from the controller and applies `next_hop` immediately.

On receiving a controller heartbeat after being in fallback, a node immediately transitions back to Normal mode and resumes accepting path-push instructions.

**Mesh fallback (local Dijkstra)** — no controller heartbeat for `HEARTBEAT_TIMEOUT_MS`, AND gossiped state table covers ≥ `MESH_FALLBACK_MIN_COVERAGE_PCT`% of nodes listed in `topology.json`:

1. Reconstruct the full undirected graph using static edge weights from `topology.json` (symmetric) and current passability from the gossiped state table
2. Run multi-source Dijkstra with all passable exit nodes as sources (each with `distance = 0`). This is the same algorithm used by the controller and produces distances for all nodes in a single pass.
3. Set `best_next_hop` to the neighbor corresponding to the first hop on the shortest path from this node to the nearest passable exit
4. Rerun whenever gossiped state changes

**Island fallback (distance-vector)** — no controller heartbeat AND gossiped coverage < `MESH_FALLBACK_MIN_COVERAGE_PCT`%:

```
my_distance = min over passable neighbors of (neighbor.distance + edge_weight)
best_next_hop = neighbor that gave that minimum
```

If no passable neighbors are available (neighbor table is empty or all entries are impassable/timed out), the node sets `my_distance = ∞` and enters isolated-node warning state (see Section 4.3).

On receiving a broadcast from a registered neighbor:
1. Update neighbor table and gossiped state table
2. Re-evaluate mode selection (above)
3. Recompute distance and `best_next_hop`
4. If `|new_distance − old_distance| > REBROADCAST_THRESHOLD` (absolute delta), rebroadcast immediately
5. Also broadcast periodically every `BROADCAST_INTERVAL_MS` regardless of change

**Exit nodes** always hold `distance = 0`, broadcast unconditionally on startup, and continue broadcasting at `BROADCAST_INTERVAL_MS`. They seed the wavefront in both fallback modes.

**Neighbor timeout**: neighbors not heard from within `NEIGHBOR_TIMEOUT_MS` are removed from the neighbor table. Gossiped state entries not updated within `GOSSIP_TIMEOUT_MS` are marked stale and excluded from Dijkstra reconstruction.

**Route poisoning (count-to-infinity mitigation):**
- A node that becomes impassable broadcasts `distance = ∞` immediately, so neighbors stop routing through it without waiting for a timeout
- If `my_distance ≥ MAX_DISTANCE` (computed from `topology.json` at startup), treat as `∞`
- In pathological looped topologies under Island fallback, distances may increment up to `MAX_DISTANCE` before settling — this is the expected worst-case behavior and is bounded

### 4.3 Evacuation Mode

Evacuation mode enables audio guidance system-wide.

**Entry:**
- Controller broadcasts `EVACUATION_START` → all nodes enter evacuation mode
- A node's own `distance` falls to `∞` (no reachable exit) → node enters evacuation mode locally

**Exit:**
- If evacuation mode was entered via `EVACUATION_START`: only `EVACUATION_END` from the controller exits it
- If evacuation mode was entered locally (distance = ∞, no `EVACUATION_START` received): the node exits evacuation mode locally when its `distance` recovers to a finite value

**Behavior in evacuation mode:**
- Speaker plays directional audio on each routing change ("proceed right toward exit")
- Speaker loops a calm guidance tone every `AUDIO_REPEAT_INTERVAL_MS`
- If `my_distance = ∞`: sign switches to warning pattern; speaker plays alert tone
- **Exit nodes** in evacuation mode play a "you have reached the exit" tone at `AUDIO_REPEAT_INTERVAL_MS` and display a fixed EXIT indicator — no directional prompt, as direction toward self is undefined

### 4.4 Output

- **Directional sign**: updates to point in the `direction` field corresponding to `best_next_hop` in the neighbor table (as defined in `topology.json`). Updates immediately on routing change.
- **Speaker**: governed by evacuation mode (Section 4.3)

### 4.5 Help Button

On press:
1. Node broadcasts distress packet: `{ type: "distress", node_id, seq, floor, location_label, timestamp_ms }`. `seq` is a monotonic uint16 counter incremented on each button press and persisted in NVS so it survives reboots.
2. Each receiving relay node forwards toward the gateway using directed routing (toward the neighbor with smallest `distance`). If a relay node has `distance = ∞`, it falls back to flooding — forwarding to all registered neighbors — to ensure the packet can still traverse the mesh. Maximum relay hops: `DISTRESS_MAX_HOPS`.
3. Each relay node maintains per-originator relay state keyed by `(node_id, seq)`. This allows a new press from the same node to be relayed even while a prior event's state is still live. Relay state is automatically purged after `DISTRESS_RELAY_TIMEOUT_MS × DISTRESS_MAX_HOPS` without receiving an ack.
4. The gateway forwards the distress packet to the controller over serial
5. Controller sends `distress_ack` back through gateway into mesh
6. A relay node that receives a `distress_ack` matching a pending `(node_id, seq)` clears that entry's relay state and stops forwarding for it
7. The originating node persists the distress event to ESP32 NVS flash and retries broadcasting at `NODE_DISTRESS_RETRY_INTERVAL_MS` until it receives an ack

---

## 5. Communication Protocol

### 5.1 ESP-NOW Packet Types

**Routing packet** — broadcast periodically and on change:
```json
{
  "type": "routing",
  "node_id": "A2",
  "distance": 12.5,
  "is_passable": true,
  "sensor_error": false,
  "temperature": 28.3,
  "co2": 0.12,
  "topology_crc32": "A3F2B1C9",
  "timestamp_ms": 123456
}
```
`timestamp_ms` is milliseconds since node boot. It is included for diagnostic and logging purposes only; receivers do not use it for ordering or deduplication. `topology_crc32` is logged by the controller to detect `topology.json` mismatches across nodes.

**Distress packet** — on help button press:
```json
{
  "type": "distress",
  "node_id": "A2",
  "seq": 7,
  "floor": 2,
  "location_label": "East Corridor",
  "timestamp_ms": 123456
}
```

**Distress ACK** — controller → gateway → mesh:
```json
{ "type": "distress_ack", "node_id": "A2", "seq": 7, "timestamp_ms": 123456 }
```

**Controller heartbeat** — controller → gateway → mesh, every `heartbeat.interval.ms`:
```json
{ "type": "heartbeat", "timestamp_ms": 123456 }
```

**Heartbeat ACK** — gateway → controller over serial only (not re-broadcast into mesh):
```json
{ "type": "heartbeat_ack", "timestamp_ms": 123456 }
```
The gateway sends this ack immediately on receiving each heartbeat packet from the controller. The controller uses presence of heartbeat ACKs to detect gateway reachability.

**Path push** — controller → gateway → specific node:
```json
{ "type": "path_push", "node_id": "A2", "next_hop_id": "B1", "path_distance": 12.5 }
```

**Evacuation control** — controller → gateway → mesh (broadcast):
```json
{ "type": "evacuation_start" }
{ "type": "evacuation_end" }
```

**Note on timestamps**: `timestamp_ms` is milliseconds since node boot (not wall clock). Values are best-effort and are used for diagnostics only. Clock synchronization is out of scope.

### 5.2 Security

Only packets from MAC addresses registered in `topology.json` are processed. Unregistered packets are silently discarded.

### 5.3 Serial Protocol (Controller ↔ Gateway)

The gateway and controller communicate over USB serial at **115200 baud**, with **newline-delimited JSON** (one JSON object per line, `\n` terminated).

- **Gateway → Controller**: all received ESP-NOW packets forwarded as-is (re-encoded as JSON lines), including routing, distress, and heartbeat_ack packets
- **Controller → Gateway**: path-push, heartbeat, evacuation-control, distress-ack, and sim-inject packets as JSON lines; the gateway broadcasts or unicasts them into the mesh accordingly

**`sim_inject` packet** — controller → gateway → mesh (used to inject simulator node state into the physical mesh):
```json
{ "type": "sim_inject", "payload": { ...routing packet for simulated node... } }
```
The gateway rebroadcasts the inner `payload` as an ESP-NOW packet on behalf of the simulated node. This is the only mechanism by which simulated nodes appear on the physical mesh.

No flow control or acknowledgement layer is defined at the serial level — packet loss on the serial link is acceptable given the fallback routing logic.

---

## 6. Central Controller Software

Runs as a Java application on a laptop (development/course demo) or Raspberry Pi 4 + UPS (fire-rated room for production). Built directly on the existing `Node.java`, `Graph.java`, and Dijkstra implementation. Yen's K-shortest paths is available in the codebase but is not used in this design.

### 6.1 Path Computation

- On startup: loads `topology.json` to build the graph; computes `MAX_DISTANCE` as sum of all edge weights
- On routing packet received: updates node sensor state and passability in the graph, then triggers a path computation cycle (debounced to at most once per `BROADCAST_INTERVAL_MS` to avoid redundant recomputation)
- Path computation: runs **multi-source Dijkstra once** with all passable exit nodes as sources (`distance = 0`). This single pass produces optimal distances and next-hops for all nodes. Pushes updated `next_hop` to each node via gateway.
- Uses existing `Node.findNearestExit()` and `Node.shortestPathTo()` as the basis, adapting to multi-source semantics
- **Debounce**: routine state updates (distance fluctuations within passable nodes) are debounced to at most one Dijkstra rerun per `BROADCAST_INTERVAL_MS`. **Route poison events** (received `is_passable = false` or `distance = ∞`) bypass the debounce and trigger an immediate rerun and path-push cycle.
- **Node timeout**: if no routing packet is received from a node within `NODE_TIMEOUT_MS`, the controller marks it unreachable, reruns Dijkstra immediately (treated as a route poison event), and flags it on the dashboard

### 6.2 Fallback Detection

The controller infers operating mode:
- **Normal**: all nodes sending routing packets within `NODE_TIMEOUT_MS`; gateway sending heartbeat ACKs
- **Mesh fallback detected**: gateway stops sending heartbeat ACKs for more than `heartbeat.interval.ms × 2`; controller stops sending path-push packets and logs the event. Resumes path-push when heartbeat ACKs resume.
- **Islands detected**: routing packets arriving from some but not all nodes beyond `NODE_TIMEOUT_MS`; dashboard highlights affected nodes

### 6.3 Monitoring Dashboard

Simple web UI served locally on the controller, accessible from any device on the local network:

- Live map of all nodes with passability status, sensor readings, and topology CRC32 match indicator
- Current routing paths overlaid on building map
- Active distress alerts with node location, timestamp, and sensor readings at time of press
- Controller mode indicator (Normal / Mesh fallback / Islands detected)
- Topology checksum mismatch alerts
- Alerts remain visible until manually acknowledged

### 6.4 Distress Handling

On distress packet received:
1. Log to timestamped file on disk
2. Display alert on dashboard
3. Send SMS to configured recipient list via cellular modem (primary)
4. POST JSON payload to configured API endpoint — building management system or fire panel (secondary)
5. If either channel fails: queue and retry every `controller.distress.notification.retry.interval.ms` until confirmed
6. Distress queue persisted to disk; controller restart does not lose undelivered packets
7. Send `distress_ack` through gateway into mesh

### 6.5 Network Connectivity

- **Ethernet**: primary for API calls
- **4G/LTE USB cellular modem**: fallback; activates automatically if ethernet is unavailable
- **No cloud dependency for routing**: all pathfinding is local; internet is only used for outbound emergency notifications

---

## 7. Node Simulator

Simulates N virtual ESP32 nodes participating in the full routing protocol. Used for development, testing, and course demo (given only 1–2 physical hardware nodes).

- Virtual nodes implement all three operating modes (Normal, Mesh fallback, Island fallback) identically to firmware — killing the controller mid-demo correctly triggers simulated fallback behavior
- Simulator communicates with the controller **in-process**: no serial bridge is needed when running without physical hardware. Simulated nodes send routing packets directly to the controller's Java layer.
- When physical hardware is present: the controller injects simulated node state into the physical mesh by sending `sim_inject` packets to the gateway over serial; the gateway rewrites the ESP-NOW source MAC to the simulated node's registered MAC and rebroadcasts into the mesh. Simulated nodes must therefore have a designated MAC address entry in `topology.json` (locally-administered MACs with the LA bit set are suitable). This makes simulated nodes indistinguishable from physical nodes on the mesh and ensures MAC filtering (Section 5.2) accepts their packets.
- Sensor readings configurable per node, changeable at runtime (e.g., simulate fire spreading)
- Supports scripted scenarios: a sequence of sensor-state changes with timestamps, for reproducible demos
- All simulated nodes appear on the dashboard labeled "SIM"

---

## 8. Configuration Files

### `config.h` (ESP32)

```c
// Sensing
#define SENSOR_POLL_INTERVAL_MS               2000
#define TEMP_THRESHOLD                        60.0f
#define CO2_THRESHOLD                         0.5f

// Routing — timing
#define NEIGHBOR_TIMEOUT_MS                   10000
#define GOSSIP_TIMEOUT_MS                     15000
#define BROADCAST_INTERVAL_MS                 3000
#define HEARTBEAT_TIMEOUT_MS                  15000   // must be > 2× heartbeat.interval.ms

// Routing — thresholds
#define REBROADCAST_THRESHOLD                 0.5f    // absolute distance delta
#define MESH_FALLBACK_MIN_COVERAGE_PCT        50      // % of topology nodes for Dijkstra mode

// MAX_DISTANCE is computed at runtime from topology.json (sum of all edge weights)

// Distress relay
#define DISTRESS_MAX_HOPS                     10
#define DISTRESS_RELAY_TIMEOUT_MS             3000
#define NODE_DISTRESS_RETRY_INTERVAL_MS       5000    // node retrying its own distress broadcast

// Audio
#define AUDIO_REPEAT_INTERVAL_MS              30000
```

### `config.properties` (Controller)

```properties
# Timing
heartbeat.interval.ms=5000
node.timeout.ms=20000

# Fallback detection
# Controller considers gateway unreachable after: heartbeat.interval.ms × 2

# External notification
sms.recipients=+1234567890,+0987654321
api.endpoint=https://bms.example.com/api/distress
api.timeout.ms=5000
controller.distress.notification.retry.interval.ms=10000

# Network
cellular.fallback.enabled=true

# Simulator
broadcast.interval.ms=3000
mesh.fallback.min.coverage.pct=50
```

All threshold and timing values are read from these files at startup. Nothing is hardcoded in logic files.

---

## 9. Error Handling Summary

| Failure | Behavior |
|---|---|
| Sensor reading out-of-range or stale | Node marks itself impassable, broadcasts `SENSOR_ERROR` flag |
| Sensor hardware fault (bus error, no response) | Same — fail safe to impassable + `SENSOR_ERROR` |
| Node power loss (silent) | Neighbors time out after `NEIGHBOR_TIMEOUT_MS`, reroute automatically |
| Node impassable (graceful poison) | Neighbors reroute within one `BROADCAST_INTERVAL_MS` |
| Controller unreachable | Nodes detect via heartbeat timeout, switch to Mesh or Island fallback automatically |
| Insufficient gossiped state for Mesh fallback | Node uses Island fallback (distance-vector) |
| No passable neighbors at all | Node sets `distance = ∞`, enters isolated-node warning state |
| Split mesh (no exit reachable for a group) | All nodes in isolated group show warning pattern + alert audio |
| Distress delivery failure (SMS/API) | Queued on disk, retried until confirmed |
| Ethernet failure | Cellular modem activates for outbound notifications |
| Serial link packet loss | Tolerated; routing fallback maintains continuity |
| topology.json CRC32 mismatch across nodes | Flagged on dashboard; does not affect routing |

---

## 10. Deployment

### Physical setup
1. Generate `topology.json` for the building (node IDs, MAC addresses, neighbor pairs with edge weights and directions, floor/location labels, exit flags)
2. Validate `topology.json` bidirectionality (all edges symmetric) before flashing
3. Load `topology.json` onto each ESP32 SPIFFS/LittleFS partition using esptool or Arduino IDE filesystem upload tool, then flash the same firmware binary to all nodes
4. Place gateway ESP32 physically adjacent to controller, connected via USB
5. Copy `topology.json` to controller working directory
6. Start controller application: it validates that `HEARTBEAT_TIMEOUT_MS > 2 × heartbeat.interval.ms` on startup and logs a fatal error if violated. Verify all nodes appear on dashboard with matching topology CRC32.

### Pre-live testing checklist
- [ ] Mark node impassable via sensor threshold → verify neighbors reroute within one `BROADCAST_INTERVAL_MS` (node sends immediate route poison)
- [ ] Kill power to a node (no graceful broadcast) → verify neighbors reroute within `NEIGHBOR_TIMEOUT_MS` + one `BROADCAST_INTERVAL_MS`
- [ ] Kill controller process → verify nodes switch to Mesh/Island fallback within `HEARTBEAT_TIMEOUT_MS`
- [ ] Restore controller → verify nodes resync to Normal mode on first heartbeat received
- [ ] Press help button → verify distress appears on dashboard, SMS sent, API called, ack received by node
- [ ] Isolate a node group → verify warning pattern activates; verify distress flooding occurs if `distance = ∞`
- [ ] Run simulator scenario (fire spread) → verify routing updates propagate correctly across all three modes
- [ ] Verify topology CRC32 mismatch detection on dashboard

---

## 11. Out of Scope

- Multi-building federation
- Integration with specific commercial fire panels (API endpoint is configurable but integration is untested)
- OTA firmware updates for ESP32 nodes
- Authentication/encryption of ESP-NOW packets (MAC filtering only)
- Wall-clock time synchronization (timestamps are milliseconds since boot, best-effort)
- Remote update of `topology.json`
