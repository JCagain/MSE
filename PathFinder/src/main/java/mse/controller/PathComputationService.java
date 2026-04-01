package mse.controller;

import mse.Exit;
import mse.Graph;
import mse.Node;

import java.util.*;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Runs multi-source Dijkstra from all passable Exit nodes outward.
 * Produces a next-hop and distance for every node in the graph.
 *
 * Debounce rules (per spec section 6.1):
 *   - Routine state updates: at most one rerun per broadcastIntervalMs.
 *   - Route poison events (isPassable=false or distance=MAX_VALUE):
 *     bypass debounce and trigger an immediate rerun.
 *
 * Results are delivered via the onResult callback.
 */
public class PathComputationService {

    private static final Logger LOG = Logger.getLogger(PathComputationService.class.getName());

    public record PathResult(String nodeId, String nextHopId, float distance) {}

    private final Graph graph;
    private final long broadcastIntervalMs;
    private final Consumer<Map<String, PathResult>> onResult;

    private long lastRunMs = 0;
    private volatile boolean pendingRoutine = false;
    private final Object lock = new Object();
    private final Thread debounceThread;
    private volatile boolean running = true;

    public PathComputationService(Graph graph, long broadcastIntervalMs,
                                   Consumer<Map<String, PathResult>> onResult) {
        this.graph = graph;
        this.broadcastIntervalMs = broadcastIntervalMs;
        this.onResult = onResult;

        debounceThread = new Thread(this::debounceLoop, "path-debounce");
        debounceThread.setDaemon(true);
        debounceThread.start();
    }

    /** Schedule a routine rerun (subject to debounce). */
    public void scheduleRoutine() {
        synchronized (lock) {
            pendingRoutine = true;
            lock.notifyAll();
        }
    }

    /** Trigger an immediate rerun, bypassing debounce (route poison event). */
    public void triggerImmediate() {
        synchronized (lock) {
            pendingRoutine = false;
            lock.notifyAll();
        }
        runDijkstra();
    }

    public void stop() {
        running = false;
        synchronized (lock) { lock.notifyAll(); }
    }

    private void debounceLoop() {
        while (running) {
            synchronized (lock) {
                while (running && !pendingRoutine) {
                    try { lock.wait(); } catch (InterruptedException ignored) {}
                }
                if (!running) break;

                long elapsed = System.currentTimeMillis() - lastRunMs;
                if (elapsed < broadcastIntervalMs) {
                    try { lock.wait(broadcastIntervalMs - elapsed); }
                    catch (InterruptedException ignored) {}
                }
                pendingRoutine = false;
            }
            if (running) runDijkstra();
        }
    }

    private void runDijkstra() {
        lastRunMs = System.currentTimeMillis();
        Map<String, PathResult> results = compute(graph);
        onResult.accept(results);
    }

    /**
     * Multi-source Dijkstra: all passable Exit nodes start at distance 0.
     * Returns next-hop (toward nearest exit) and distance for each node.
     *
     * Uses System.identityHashCode as PQ key (same pattern as Node.java).
     */
    public static Map<String, PathResult> compute(Graph graph) {
        PriorityQueue<float[]> pq = new PriorityQueue<>(Comparator.comparingDouble(a -> a[0]));
        Map<Node, Float> dist = new HashMap<>();
        Map<Node, Node> prev = new HashMap<>();  // prev[v] = neighbor of v closer to exit
        Map<Integer, Node> hashToNode = new HashMap<>();

        // Seed: all passable exits at distance 0
        for (Node node : graph.getAllNodes()) {
            if (node instanceof Exit && node.isPassable()) {
                dist.put(node, 0f);
                hashToNode.put(System.identityHashCode(node), node);
                pq.offer(new float[]{0f, System.identityHashCode(node)});
            }
        }

        while (!pq.isEmpty()) {
            float[] cur = pq.poll();
            float curDist = cur[0];
            Node curNode = hashToNode.get((int) cur[1]);

            if (curDist > dist.getOrDefault(curNode, Float.MAX_VALUE)) continue;

            for (Map.Entry<Node, Float> entry : curNode.getNeighbors().entrySet()) {
                Node next = entry.getKey();
                if (!next.isPassable()) continue;

                float newDist = curDist + entry.getValue();
                if (newDist < dist.getOrDefault(next, Float.MAX_VALUE)) {
                    dist.put(next, newDist);
                    prev.put(next, curNode);  // curNode is closer to exit — this is next_hop for next
                    hashToNode.put(System.identityHashCode(next), next);
                    pq.offer(new float[]{newDist, System.identityHashCode(next)});
                }
            }
        }

        Map<String, PathResult> results = new LinkedHashMap<>();
        for (Node node : graph.getAllNodes()) {
            float d = dist.getOrDefault(node, Float.MAX_VALUE);
            Node nextHopNode = prev.get(node);
            results.put(node.getId(), new PathResult(
                node.getId(),
                nextHopNode != null ? nextHopNode.getId() : null,
                d
            ));
        }
        return results;
    }
}
