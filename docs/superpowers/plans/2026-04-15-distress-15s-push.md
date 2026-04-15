# Distress Signal + 15s Proactive Push — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace on-demand path computation with a 15s proactive push loop, and reinterpret the ESP32 button press (`search`) as a distress signal displayed as a banner on the matplotlib dashboard.

**Architecture:** The main loop tracks `last_push_time`; every 15 seconds it computes a fresh direction and sends it to the ESP32. When `search` arrives it records distress state (`count`, `time`) and redraws the map with a red banner overlay. No changes to the ESP32 sketch or serial wire format.

**Tech Stack:** Python 3, matplotlib (TkAgg for runtime / Agg for tests), networkx, pyserial.

---

## File Map

| File | Action | Responsibility |
|------|--------|----------------|
| `NEW/node7_controller.py` | Modify | Add `distress_info` param to `draw_full_result`; rewrite `main()` with 15s timer + distress state |
| `NEW/test_node7_controller.py` | Create | Unit tests for `draw_full_result` with and without `distress_info` |

---

### Task 1: Make `draw_full_result` testable (move backend selection into `main`)

**Files:**
- Modify: `NEW/node7_controller.py`

The top of the file currently calls `matplotlib.use('TkAgg')` at module level, which breaks imports
in a headless test environment. Move it into `main()` so importing the module in tests works cleanly.

- [ ] **Step 1: Move `matplotlib.use('TkAgg')` into `main()`**

In `NEW/node7_controller.py`, the current top-of-file block is:

```python
import matplotlib
matplotlib.use('TkAgg')
import matplotlib.pyplot as plt
```

Replace it with:

```python
import matplotlib
import matplotlib.pyplot as plt
```

Then, inside `main()`, add `matplotlib.use('TkAgg')` as the very first line:

```python
def main():
    matplotlib.use('TkAgg')
    ser = serial.Serial(PORT, BAUD, timeout=1)
    ...
```

- [ ] **Step 2: Verify the script still runs (manual check)**

```bash
cd /home/jc/Projects/MSE
.venv/bin/python -c "import NEW.node7_controller"
```

Expected: no errors, no window opens.

- [ ] **Step 3: Commit**

```bash
git add NEW/node7_controller.py
git commit -m "refactor: move matplotlib backend selection into main()"
```

---

### Task 2: Add `distress_info` parameter to `draw_full_result` (TDD)

**Files:**
- Create: `NEW/test_node7_controller.py`
- Modify: `NEW/node7_controller.py`

- [ ] **Step 1: Write the failing tests**

Create `NEW/test_node7_controller.py`:

```python
import os
os.environ['MPLBACKEND'] = 'Agg'   # must be before any matplotlib import

import sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import matplotlib.pyplot as plt
import networkx as nx
import mapnode20 as sim
from node7_controller import draw_full_result


def _make_result():
    """Build a minimal result dict the same way node7_controller does."""
    edge_data, node_temp, node_co2, fire_node, scenario = sim.generate_fire_data(7)

    G = nx.Graph()
    G_no_block = nx.Graph()
    G_physical = nx.Graph()
    G.add_nodes_from(sim.all_nodes)
    G_no_block.add_nodes_from(sim.all_nodes)
    G_physical.add_nodes_from(sim.all_nodes)

    for u, v, L, T, C, W in edge_data:
        G.add_edge(u, v, weight=W)
        G_no_block.add_edge(u, v, weight=W if W != float('inf') else 10000.0)
        G_physical.add_edge(u, v, weight=L)

    guide, exits = sim.compute_escape(G, G_physical, G_no_block, fire_node)
    _, main_path, main_cost, backup_path, backup_cost = guide[7]

    return {
        "direction": "right",
        "scenario": scenario,
        "fire_node": fire_node,
        "main_path": main_path,
        "backup_path": backup_path,
        "path_type": "MAIN",
        "edge_data": edge_data,
        "node_temp": node_temp,
        "node_co2": node_co2,
        "guide": guide,
        "exits": exits,
    }


def test_draw_without_distress_does_not_raise():
    fig = plt.figure()
    draw_full_result(fig, _make_result(), clicked_node=7)
    plt.close(fig)


def test_draw_with_distress_info_shows_banner():
    fig = plt.figure()
    distress_info = {"count": 3, "time": 1713181385.0}  # fixed Unix timestamp
    draw_full_result(fig, _make_result(), clicked_node=7, distress_info=distress_info)

    # ax_map is the second subplot (index 1)
    ax_map = fig.axes[1]
    texts = [t.get_text() for t in ax_map.texts]
    assert any("DISTRESS" in t for t in texts), \
        f"Expected a text containing 'DISTRESS' in ax_map, found: {texts}"
    plt.close(fig)


def test_draw_with_distress_info_shows_count():
    fig = plt.figure()
    distress_info = {"count": 5, "time": 1713181385.0}
    draw_full_result(fig, _make_result(), clicked_node=7, distress_info=distress_info)

    ax_map = fig.axes[1]
    texts = [t.get_text() for t in ax_map.texts]
    assert any("×5" in t for t in texts), \
        f"Expected count '×5' in ax_map texts, found: {texts}"
    plt.close(fig)
```

- [ ] **Step 2: Run to verify tests fail**

```bash
cd /home/jc/Projects/MSE
MPLBACKEND=Agg .venv/bin/python -m pytest NEW/test_node7_controller.py -v
```

Expected: `test_draw_without_distress_does_not_raise` PASS (existing signature),
`test_draw_with_distress_info_shows_banner` and `test_draw_with_distress_info_shows_count`
FAIL with `TypeError: draw_full_result() got an unexpected keyword argument 'distress_info'`.

- [ ] **Step 3: Add `distress_info` parameter and banner to `draw_full_result`**

Change the function signature from:

```python
def draw_full_result(fig, result, clicked_node=7):
```

to:

```python
def draw_full_result(fig, result, clicked_node=7, distress_info=None):
```

Then add the following block at the very end of `draw_full_result`, just before
`plt.tight_layout()` and `plt.draw()`:

```python
    if distress_info is not None:
        import datetime
        ts = datetime.datetime.fromtimestamp(distress_info["time"]).strftime("%H:%M:%S")
        banner = (
            f"\U0001f198 DISTRESS \u2014 Node 7 called for help"
            f"  (\u00d7{distress_info['count']})  [{ts}]"
        )
        ax_map.text(
            0.5, 0.015, banner,
            transform=ax_map.transAxes,
            fontsize=13,
            fontweight="bold",
            color="white",
            ha="center",
            va="bottom",
            bbox=dict(boxstyle="round,pad=0.4", facecolor="red", alpha=0.92),
        )
```

- [ ] **Step 4: Run tests to verify all pass**

```bash
cd /home/jc/Projects/MSE
MPLBACKEND=Agg .venv/bin/python -m pytest NEW/test_node7_controller.py -v
```

Expected output:
```
PASSED NEW/test_node7_controller.py::test_draw_without_distress_does_not_raise
PASSED NEW/test_node7_controller.py::test_draw_with_distress_info_shows_banner
PASSED NEW/test_node7_controller.py::test_draw_with_distress_info_shows_count
3 passed
```

- [ ] **Step 5: Commit**

```bash
git add NEW/test_node7_controller.py NEW/node7_controller.py
git commit -m "feat: add distress_info banner to draw_full_result"
```

---

### Task 3: Rewrite `main()` with 15s push timer and distress state

**Files:**
- Modify: `NEW/node7_controller.py:313-349`

- [ ] **Step 1: Replace the `main()` function**

Replace the entire `main()` function (from `def main():` to the end of the `while True` loop)
with the following:

```python
def main():
    matplotlib.use('TkAgg')
    ser = serial.Serial(PORT, BAUD, timeout=1)
    time.sleep(2)

    plt.ion()
    fig = plt.figure(figsize=(14, 7), dpi=100)

    last_push_time = 0.0   # 0 forces an immediate push on startup
    distress_count = 0
    distress_time = None
    last_result = None

    print("Python controller ready. Pushing direction every 15s.")

    while True:
        # --- 15-second proactive push ---
        if time.time() - last_push_time >= 15:
            last_result = compute_node7_result()

            print("------ Scheduled Push ------")
            print(f"Scenario   : {last_result['scenario']}")
            print(f"Fire Node  : {last_result['fire_node']}")
            print(f"Path Type  : {last_result['path_type']}")
            print(f"Main Path  : {last_result['main_path']}")
            print(f"Backup Path: {last_result['backup_path']}")
            print(f"Send       : {last_result['direction']}")
            print("----------------------------")

            distress_info = (
                {"count": distress_count, "time": distress_time}
                if distress_time is not None else None
            )
            draw_full_result(fig, last_result, clicked_node=7,
                             distress_info=distress_info)
            plt.pause(0.1)

            ser.write((last_result["direction"] + '\n').encode())
            ser.flush()
            last_push_time = time.time()

        # --- Serial read ---
        if ser.in_waiting > 0:
            msg = ser.readline().decode('utf-8').strip()
            if msg:
                print(f"From ESP32: {msg}")

            if msg == "search":
                distress_count += 1
                distress_time = time.time()
                print(f"DISTRESS received (#{distress_count})")

                if last_result is not None:
                    distress_info = {"count": distress_count, "time": distress_time}
                    draw_full_result(fig, last_result, clicked_node=7,
                                     distress_info=distress_info)
                    plt.pause(0.1)

        plt.pause(0.05)   # keep GUI responsive between iterations
```

- [ ] **Step 2: Run tests to verify nothing broke**

```bash
cd /home/jc/Projects/MSE
MPLBACKEND=Agg .venv/bin/python -m pytest NEW/test_node7_controller.py -v
```

Expected: 3 passed.

- [ ] **Step 3: Commit**

```bash
git add NEW/node7_controller.py
git commit -m "feat: 15s proactive push + distress state in main loop"
```

---

## Manual Verification Checklist

After completing all tasks, run the controller with the ESP32 connected and verify:

1. On startup: a direction is sent to ESP32 immediately (within 1s).
2. Every 15s: map redraws and a new direction is sent — check terminal output for "Scheduled Push".
3. Press the button: terminal prints `From ESP32: search` and `DISTRESS received (#1)`.
   Map redraws with the red DISTRESS banner at the bottom.
4. Press again: count increments to `(×2)` and timestamp updates.
5. Wait for the next 15s push: map redraws and the DISTRESS banner is still visible.
