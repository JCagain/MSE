# Design: ESP32 Gateway Path Receiver

**Date:** 2026-04-04
**Status:** Approved

## Overview

Implement the ESP32 gateway firmware's `path_receiver` module. The gateway is connected to the
Arduino Mega via UART (115200 baud). After each Dijkstra run the Mega broadcasts one `path_push`
packet per node. The gateway reads those packets, filters for its own `node_id`, and exposes the
current evacuation direction via `path_receiver_get_direction()`.

No mesh networking is required — the gateway communicates only with the Mega over UART.

---

## Protocol Change: `path_push` packet

The Mega's `path_push` packet changes from `next_hop_id` to `direction`:

**Before (MEGA firmware plan as written):**
```json
{"type": "path_push", "node_id": "1B", "next_hop_id": "1C", "path_distance": 7.0}
```

**After (this spec):**
```json
{"type": "path_push", "node_id": "1B", "direction": "right"}
```

### Impact on MEGA firmware plan

Two tasks in `docs/superpowers/plans/2026-04-04-mega-firmware.md` must be updated before
flashing:

1. **`TopologyCompiler`** (Java, already implemented) — emit a
   `DIRECTIONS[NUM_NODES][MAX_NEIGHBORS]` `const char*` array in `topology.h`, parallel to
   `NEIGHBOR_IDX`. Source: the `direction` field on each neighbor entry in `topology.json`.

2. **`serial_mesh.cpp` Task 5** — when building `path_push`, look up
   `DIRECTIONS[i][j]` for the neighbor index that matches `node_states[i].next_hop`, and
   emit `direction` instead of `next_hop_id`.

The laptop `state_snapshot` packet is unaffected.

---

## File Structure

```
esp32_gateway/
├── esp32_gateway.ino    # MY_NODE_ID define, setup(), loop()
├── path_receiver.h      # public API
└── path_receiver.cpp    # line buffer, JSON parse, direction store
```

---

## Module: `path_receiver`

### Public API (`path_receiver.h`)

```c
// Initialise the module. Call once from setup().
void path_receiver_init(void);

// Read available UART bytes, parse complete lines, update stored direction.
// Call every iteration of loop().
void path_receiver_poll(void);

// Return the most recently received direction for MY_NODE_ID.
// Returns "left", "right", or NULL if no matching packet has been received yet.
const char* path_receiver_get_direction(void);
```

### Internals (`path_receiver.cpp`)

- 128-byte static line buffer accumulates UART bytes from `Serial`.
- On `\n`: null-terminates the buffer and calls `handle_line()`.
- `handle_line()` parses with `ArduinoJson` (`StaticJsonDocument<256>`).
  - Ignores packets where `type != "path_push"`.
  - Ignores packets where `node_id != MY_NODE_ID`.
  - On match: copies `direction` into a 16-byte static string buffer.
- `path_receiver_get_direction()` returns a pointer to that buffer, or `NULL` if it has never
  been written.

### `esp32_gateway.ino`

```cpp
#define MY_NODE_ID "1B"   // must match this device's node_id in topology.json

void setup() {
    path_receiver_init();
}

void loop() {
    path_receiver_poll();
    // caller reads path_receiver_get_direction() whenever needed
}
```

**Hardware:** UART0 (`Serial`) at 115200 baud. Mega TX1 → ESP32 RX, Mega RX1 ← ESP32 TX.

---

## Dependencies

| Library | Version | Use |
|---|---|---|
| ArduinoJson | 6.21.x | JSON parsing |

Install via Arduino IDE: Sketch → Include Library → Manage Libraries → "ArduinoJson" by Benoit Blanchon, version 6.x.

---

## Out of Scope

- Mesh networking (no other ESP32 nodes need implementation)
- Sending `routing` packets to the Mega
- Distress button handling
- Driving physical arrow hardware (caller uses `get_direction()` for that)
