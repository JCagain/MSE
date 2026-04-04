# ESP32 Gateway Path Receiver Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a `path_receiver` module to the ESP32 gateway sketch that receives `path_push` packets from the Arduino Mega over UART and exposes the current evacuation direction.

**Architecture:** Three tasks: (1) update `TopologyCompiler` to emit a `DIRECTIONS` array in `topology.h`; (2) patch the MEGA firmware plan so `path_push` uses `direction` instead of `next_hop_id`; (3) implement the `path_receiver` Arduino module with a test sketch.

**Tech Stack:** Java 17 / Maven (TopologyCompiler), Arduino C++ / ArduinoJson 6.21.x (ESP32 firmware)

---

## File Map

| Action | Path | Responsibility |
|---|---|---|
| Modify | `src/main/java/mse/topology/TopologyCompiler.java` | Emit `DIRECTIONS[][]` array |
| Modify | `src/test/java/mse/topology/TopologyCompilerTest.java` | Test `DIRECTIONS` output |
| Modify | `docs/superpowers/plans/2026-04-04-mega-firmware.md` | Update Task 1 and Task 5 for direction change |
| Create | `esp32_gateway/config.h` | `#define MY_NODE_ID` — shared by .ino and .cpp |
| Create | `esp32_gateway/path_receiver.h` | Public API |
| Create | `esp32_gateway/path_receiver.cpp` | Line buffer + JSON parse + direction store |
| Create | `esp32_gateway/esp32_gateway.ino` | `setup()` / `loop()` |
| Create | `esp32_gateway/test_path_receiver/test_path_receiver.ino` | Self-contained test sketch |

---

### Task 1: Update TopologyCompiler to emit DIRECTIONS array

**Context:** `TopologyCompiler.compile()` already emits `NEIGHBOR_IDX[][]` and `WEIGHTS[][]`.
`NeighborJson` already has a `direction` field (parsed from topology.json). We add a parallel
`DIRECTIONS[][]` array so the Mega firmware can look up the arrow direction for each neighbor.

**Files:**
- Modify: `src/main/java/mse/topology/TopologyCompiler.java`
- Modify: `src/test/java/mse/topology/TopologyCompilerTest.java`

- [ ] **Step 1: Add failing tests to `TopologyCompilerTest`**

Open `src/test/java/mse/topology/TopologyCompilerTest.java` and add these two tests inside
the class (after the existing tests):

```java
@Test
void compile_directionsArrayPresent() throws IOException {
    assertTrue(compile().contains("DIRECTIONS[NUM_NODES][MAX_NEIGHBORS]"));
}

@Test
void compile_directionsCorrectForFirstNode() throws IOException {
    // 1A's neighbors in sample-topology.json: 1B (right), 1C (forward)
    String out = compile();
    assertTrue(out.contains("\"right\", \"forward\""), "1A directions: " + out);
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
mvn test -Dtest=TopologyCompilerTest -q
```

Expected: 2 failures — `compile_directionsArrayPresent` and `compile_directionsCorrectForFirstNode`.

- [ ] **Step 3: Add DIRECTIONS emission to `TopologyCompiler.compile()`**

Open `src/main/java/mse/topology/TopologyCompiler.java`. At line 88, after the `WEIGHTS` block
closes (`sb.append("};\n");`), append:

```java
        sb.append("\nstatic const char* const DIRECTIONS[NUM_NODES][MAX_NEIGHBORS] = {\n");
        for (int i = 0; i < n; i++) {
            List<NeighborJson> nbs = neighbors(nodes.get(i));
            sb.append("    {");
            for (int j = 0; j < maxN; j++) {
                if (j < nbs.size()) {
                    String dir = nbs.get(j).direction != null ? nbs.get(j).direction : "";
                    sb.append('"').append(dir).append('"');
                } else {
                    sb.append("\"\"");
                }
                if (j < maxN - 1) sb.append(", ");
            }
            sb.append("}");
            if (i < n - 1) sb.append(",");
            sb.append("   // ").append(nodes.get(i).nodeId).append("\n");
        }
        sb.append("};\n");
```

- [ ] **Step 4: Run all tests**

```bash
mvn test -q
```

Expected: `Tests run: 16, Failures: 0, Errors: 0` and `BUILD SUCCESS`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/mse/topology/TopologyCompiler.java \
        src/test/java/mse/topology/TopologyCompilerTest.java
git commit -m "feat(topology): TopologyCompiler emits DIRECTIONS array for Mega firmware"
```

---

### Task 2: Patch MEGA firmware plan for direction-based path_push

**Context:** The MEGA firmware plan at `docs/superpowers/plans/2026-04-04-mega-firmware.md`
was written before we changed `path_push` to use `direction` instead of `next_hop_id`. Two
sections must be updated so the plan is correct when executed.

**Files:**
- Modify: `docs/superpowers/plans/2026-04-04-mega-firmware.md`

- [ ] **Step 1: Update Task 1 — expected topology.h output**

In Task 1 Step 3, the "Expected content" block shows the topology.h contents. Add the
`DIRECTIONS` array after the `WEIGHTS` block. Replace the end of the expected content block:

Old (the final lines of the expected block):
```
static const float WEIGHTS[NUM_NODES][MAX_NEIGHBORS] = {
    {5.0f, 3.0f},
    {5.0f, 7.0f},
    {3.0f, 4.0f},
    {7.0f, 4.0f},
};
```

New (add DIRECTIONS after WEIGHTS):
```
static const float WEIGHTS[NUM_NODES][MAX_NEIGHBORS] = {
    {5.0f, 3.0f},
    {5.0f, 7.0f},
    {3.0f, 4.0f},
    {7.0f, 4.0f},
};

static const char* const DIRECTIONS[NUM_NODES][MAX_NEIGHBORS] = {
    {"right", "forward"},   // 1A
    {"left", "forward"},    // 1B
    {"back", "right"},      // 1C
    {"back", "left"},       // 1Exit-A
};
```

- [ ] **Step 2: Update Task 5 — `mesh_broadcast_path_push` in serial_mesh.cpp**

In Task 5 Step 2, find `mesh_broadcast_path_push()`. Replace its body with:

```cpp
void mesh_broadcast_path_push(void) {
    for (int i = 0; i < NUM_NODES; i++) {
        if (node_states[i].next_hop < 0) continue;  // exit node or unreachable

        // Find which neighbor slot corresponds to next_hop, look up direction
        const char* dir = "";
        for (int j = 0; j < MAX_NEIGHBORS; j++) {
            if (NEIGHBOR_IDX[i][j] == node_states[i].next_hop) {
                dir = DIRECTIONS[i][j];
                break;
            }
        }

        StaticJsonDocument<128> doc;
        doc["type"]      = "path_push";
        doc["node_id"]   = NODE_IDS[i];
        doc["direction"] = dir;
        serializeJson(doc, Serial1);
        Serial1.println();
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add docs/superpowers/plans/2026-04-04-mega-firmware.md
git commit -m "docs(mega): update firmware plan for direction-based path_push"
```

---

### Task 3: Implement path_receiver module + ESP32 gateway sketch

**Context:** The ESP32 gateway is connected to the Mega via UART (`Serial` at 115200 baud).
The Mega sends newline-delimited JSON. The `path_receiver` module buffers bytes, parses
complete lines, and stores the direction from any `path_push` packet whose `node_id` matches
`MY_NODE_ID` (defined in `config.h`).

`MY_NODE_ID` is in `config.h` (not the .ino) so both `esp32_gateway.ino` and
`path_receiver.cpp` can include it — Arduino compiles .cpp files separately from .ino.

**Files:**
- Create: `esp32_gateway/config.h`
- Create: `esp32_gateway/path_receiver.h`
- Create: `esp32_gateway/path_receiver.cpp`
- Create: `esp32_gateway/esp32_gateway.ino`
- Create: `esp32_gateway/test_path_receiver/test_path_receiver.ino`

- [ ] **Step 1: Create the test sketch first (TDD)**

Create `esp32_gateway/test_path_receiver/test_path_receiver.ino`. This sketch is
self-contained — it inlines the parsing logic so it can be tested without wiring.
Upload to the ESP32 and open Serial Monitor (115200 baud).

```cpp
// esp32_gateway/test_path_receiver/test_path_receiver.ino
//
// Tests the path_receiver parsing logic in isolation.
// Upload to ESP32 and open Serial Monitor at 115200 baud.
// Expected output: ALL TESTS PASSED

#include <ArduinoJson.h>
#include <string.h>

#define MY_NODE_ID "1B"

static char direction_buf[16];
static bool has_direction = false;

static void feed_line(const char* line) {
    StaticJsonDocument<256> doc;
    if (deserializeJson(doc, line) != DeserializationError::Ok) return;
    const char* type = doc["type"] | "";
    if (strcmp(type, "path_push") != 0) return;
    const char* node_id = doc["node_id"] | "";
    if (strcmp(node_id, MY_NODE_ID) != 0) return;
    const char* dir = doc["direction"] | "";
    strncpy(direction_buf, dir, sizeof(direction_buf) - 1);
    direction_buf[sizeof(direction_buf) - 1] = '\0';
    has_direction = true;
}

static bool check(const char* label, bool condition) {
    if (!condition) {
        Serial.print("FAIL: ");
        Serial.println(label);
        return false;
    }
    return true;
}

static void reset() {
    has_direction = false;
    direction_buf[0] = '\0';
}

void setup() {
    Serial.begin(115200);
    while (!Serial) {}

    bool pass = true;

    // Test 1: own packet sets direction to "right"
    reset();
    feed_line("{\"type\":\"path_push\",\"node_id\":\"1B\",\"direction\":\"right\"}");
    pass &= check("own packet sets has_direction", has_direction);
    pass &= check("direction is right", strcmp(direction_buf, "right") == 0);

    // Test 2: different node_id is ignored
    reset();
    feed_line("{\"type\":\"path_push\",\"node_id\":\"1A\",\"direction\":\"left\"}");
    pass &= check("ignores other node", !has_direction);

    // Test 3: wrong packet type is ignored
    reset();
    feed_line("{\"type\":\"state_snapshot\",\"node_id\":\"1B\",\"direction\":\"left\"}");
    pass &= check("ignores wrong type", !has_direction);

    // Test 4: malformed JSON is silently ignored
    reset();
    feed_line("not json at all");
    pass &= check("malformed JSON ignored", !has_direction);

    // Test 5: direction updates on successive packets
    reset();
    feed_line("{\"type\":\"path_push\",\"node_id\":\"1B\",\"direction\":\"left\"}");
    feed_line("{\"type\":\"path_push\",\"node_id\":\"1B\",\"direction\":\"right\"}");
    pass &= check("direction updates to latest", strcmp(direction_buf, "right") == 0);

    if (pass) Serial.println("ALL TESTS PASSED");
}

void loop() {}
```

- [ ] **Step 2: Upload test sketch and verify output**

In Arduino IDE: select board "ESP32 Dev Module" (or your specific variant), open
`esp32_gateway/test_path_receiver/test_path_receiver.ino`, upload, open Serial Monitor
at 115200 baud.

Expected:
```
ALL TESTS PASSED
```

If any FAIL lines appear, fix the logic before continuing.

- [ ] **Step 3: Create `config.h`**

Create `esp32_gateway/config.h`:

```cpp
// esp32_gateway/config.h
#pragma once

// Change this to match this device's node_id in topology.json.
#define MY_NODE_ID "1B"
```

- [ ] **Step 4: Create `path_receiver.h`**

Create `esp32_gateway/path_receiver.h`:

```cpp
// esp32_gateway/path_receiver.h
#pragma once

// Initialise the module. Starts Serial at 115200. Call once from setup().
void path_receiver_init(void);

// Read available UART bytes and parse complete lines.
// Call every iteration of loop().
void path_receiver_poll(void);

// Return the most recently received direction for MY_NODE_ID.
// Returns "left", "right", or NULL if no matching packet received yet.
const char* path_receiver_get_direction(void);

// For testing: feed a complete JSON line directly (bypasses UART).
void path_receiver_feed_line(const char* line);
```

- [ ] **Step 5: Create `path_receiver.cpp`**

Create `esp32_gateway/path_receiver.cpp`:

```cpp
// esp32_gateway/path_receiver.cpp
#include "path_receiver.h"
#include "config.h"
#include <Arduino.h>
#include <ArduinoJson.h>
#include <string.h>

static char line_buf[128];
static int  line_pos      = 0;
static char direction_buf[16];
static bool has_direction = false;

void path_receiver_init(void) {
    Serial.begin(115200);
    line_pos      = 0;
    has_direction = false;
    direction_buf[0] = '\0';
}

void path_receiver_feed_line(const char* line) {
    StaticJsonDocument<256> doc;
    if (deserializeJson(doc, line) != DeserializationError::Ok) return;
    const char* type = doc["type"] | "";
    if (strcmp(type, "path_push") != 0) return;
    const char* node_id = doc["node_id"] | "";
    if (strcmp(node_id, MY_NODE_ID) != 0) return;
    const char* dir = doc["direction"] | "";
    strncpy(direction_buf, dir, sizeof(direction_buf) - 1);
    direction_buf[sizeof(direction_buf) - 1] = '\0';
    has_direction = true;
}

void path_receiver_poll(void) {
    while (Serial.available()) {
        char c = (char)Serial.read();
        if (c == '\n') {
            line_buf[line_pos] = '\0';
            path_receiver_feed_line(line_buf);
            line_pos = 0;
        } else if (line_pos < 127) {
            line_buf[line_pos++] = c;
        }
    }
}

const char* path_receiver_get_direction(void) {
    return has_direction ? direction_buf : nullptr;
}
```

- [ ] **Step 6: Create `esp32_gateway.ino`**

Create `esp32_gateway/esp32_gateway.ino`:

```cpp
// esp32_gateway/esp32_gateway.ino
//
// MSE ESP32 Gateway — receives path_push from Arduino Mega via UART
//
// Wiring:
//   ESP32 RX (GPIO 3)  ← Mega TX1 (pin 18)
//   ESP32 TX (GPIO 1)  → Mega RX1 (pin 19)
//   GND                — GND

#include "config.h"
#include "path_receiver.h"

void setup() {
    path_receiver_init();
}

void loop() {
    path_receiver_poll();

    // Use path_receiver_get_direction() wherever the direction is needed.
    // Returns "left", "right", or NULL if not yet received.
}
```

- [ ] **Step 7: Verify the full sketch compiles**

In Arduino IDE: open `esp32_gateway/esp32_gateway.ino`, select your ESP32 board,
Sketch → Verify/Compile.

Expected: no errors. If ArduinoJson is missing: Sketch → Include Library →
Manage Libraries → search "ArduinoJson" by Benoit Blanchon → install version 6.x.

- [ ] **Step 8: Commit**

```bash
git add esp32_gateway/
git commit -m "feat(esp32): path_receiver module — parse path_push direction from Mega UART"
```
