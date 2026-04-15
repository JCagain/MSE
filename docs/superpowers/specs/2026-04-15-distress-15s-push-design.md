# Distress Signal + 15s Proactive Push — Design Spec

**Date:** 2026-04-15
**Scope:** `NEW/node7_controller.py` only. No changes to ESP32 sketch or serial protocol.

---

## Overview

Currently the laptop computes an evacuation direction only when the ESP32 button is pressed
(`search` → compute → reply). This design replaces that with:

1. **Proactive 15s push** — the laptop computes Node 7's evacuation direction every 15 seconds
   and sends `left`/`right` to the ESP32 unprompted.
2. **Distress handling** — when `search` arrives from the ESP32 (button press), the laptop
   records it as a distress event and redraws the dashboard with a distress banner. No
   path recomputation is triggered on button press.

---

## Architecture & Data Flow

### 15s push timer

- Track `last_push_time = 0.0` (forces an immediate push on startup).
- In the main loop: if `time.time() - last_push_time >= 15`, compute result, send direction,
  redraw map, update `last_push_time`.
- Push also redraws the map with the latest scenario.

### Distress handling

- State variables: `distress_count = 0`, `distress_time = None`.
- When `search` is received: increment `distress_count`, set `distress_time = time.time()`,
  redraw map (reusing the last computed `result`).
- No serial response is sent on `search`.

---

## Dashboard Changes

`draw_full_result(fig, result, clicked_node, distress_info=None)`

- `distress_info`: `None` or `{"count": int, "time": float}`.
- When not `None`, draw a red overlay banner at the bottom of `ax_map`:

  ```
  🆘 DISTRESS — Node 7 called for help  (×3)  [14:23:05]
  ```

- Count shows total button presses this session. Timestamp shows most recent press.
- Banner persists across redraws until the process is restarted.

---

## Serial Protocol

No changes. Plain text at 9600 baud.

| Direction | Message | Meaning (new interpretation) |
|-----------|---------|-------------------------------|
| ESP32 → Laptop | `search\n` | Button pressed — record distress |
| Laptop → ESP32 | `left\n` or `right\n` | Evacuation direction (pushed every 15s) |

The ESP32 sketch (`sketch_apr8a.ino`) is unchanged.

---

## Out of Scope

- No `distress_ack` packet back to ESP32.
- No JSON packet format changes.
- No generalisation to other nodes (still hardcoded to Node 7).
