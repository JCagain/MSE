# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run

All source files are in `PathFinder/`. There are two separate compilation targets:

**GUI application (self-contained):**
```bash
cd PathFinder
javac GraphGUI.java
java GraphGUI
```

**CLI demo:**
```bash
cd PathFinder
javac Node.java Graph.java main.java
java Main
```

> Note: `main.java` is currently out of sync with `Node.java`. It calls `new Node("A", true, 25.0f, 0.01f)` with a boolean passable parameter, but `Node.java` was refactored to use threshold-based passability — the constructor no longer accepts a boolean. `main.java` will not compile until updated.

There is no build tool (Maven/Gradle). Compile individual `.java` files directly with `javac`.

## Architecture

### Two parallel implementations

There are effectively two independent codebases living side by side:

1. **`GraphGUI.java`** — A single self-contained file containing everything needed for the Swing GUI: inlined redefinitions of `Node`, `Exit`, and `PathCandidate`, plus the `GraphGUI` JFrame subclass with a custom `GraphCanvas` panel. This is the primary deliverable.

2. **`Node.java` + `Graph.java` + `main.java`** — A separate CLI implementation. `Node.java` is the canonical, well-documented version of the node model. `Graph.java` manages node registration and structural validation (every node must have at least one neighbor). These files do not import or depend on `GraphGUI.java`.

The two implementations share the same domain model design but are not linked at the source level — changes to `Node.java` are not reflected in `GraphGUI.java` and vice versa.

### Core domain model

**`Node`**: Represents a room/zone in a building. Passability is determined dynamically:
- Default: passable if `temperature <= temperatureThreshold` AND `gasConcentration <= gasConcentrationThreshold`
- A manual `passableOverride` (boolean) takes precedence when set, bypassing thresholds
- Default thresholds: 60°C temperature, 0.5 gas concentration

**`Exit extends Node`**: A node that serves as an evacuation exit. Can be force-blocked via passable override (e.g. structurally damaged exit). Defined in `GraphGUI.java`; `Exit.class` compiled from there.

**`PathCandidate`**: A value object holding a path (ordered `List<Node>`) and its total distance. Implements `Comparable` for use in priority queues. Defined in `GraphGUI.java`.

**`Graph`**: Registry of nodes by ID with a `validate()` method that checks every node has at least one connection.

### Pathfinding algorithms (on `Node`)

- **`shortestPathTo(Node target)`** — Dijkstra; always allows reaching `target` regardless of its passable state, but skips other impassable intermediate nodes.
- **`findNearestExit()`** — Dijkstra variant that searches for the nearest passable `Exit` instance.
- **`findKShortestPaths(Node target, int k)`** — Yen's K-Shortest Paths algorithm. Uses an internal `dijkstraWithExclusions()` helper to compute spur paths with temporarily removed edges and nodes.

Dijkstra uses `System.identityHashCode` as node identity key in the priority queue (stored in a parallel `hashToNode` map) because `float[]` arrays are used for the PQ entries.

### GUI (`GraphGUI`)

Two interaction modes (toggled via UI button):
- `CHANGE_PASSABILITY`: click a node to toggle its passable override
- `FIND_PATH`: click source then destination to compute and display up to 3 shortest paths (rendered in gold/pink/cyan)

The `GraphCanvas` inner class handles all rendering and mouse interaction. Nodes are color-coded: blue = regular passable, green = exit passable, red = impassable.
