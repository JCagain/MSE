package mse;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Represents a node in a weighted graph.
 * Each node tracks traversal state, environmental conditions,
 * and maintains a weighted adjacency list to its neighbors.
 *
 * <p>Passability is derived dynamically from environmental thresholds
 * rather than stored as a fixed flag. Use {@link #setPassable(boolean)}
 * to apply a manual override, or {@link #clearPassableOverride()} to
 * revert to threshold-based evaluation.
 */
public class Node {
    private final String id;

    // Environmental conditions
    private float temperature;
    private float gasConcentration;

    // Passability: manual override takes precedence when set
    private Boolean passableOverride;           // null = use threshold logic
    private float temperatureThreshold;         // node is impassable above this temperature
    private float gasConcentrationThreshold;    // node is impassable above this concentration

    // Default thresholds — can be changed per-node or globally
    public static final float DEFAULT_TEMPERATURE_THRESHOLD     = 60.0f;
    public static final float DEFAULT_GAS_CONCENTRATION_THRESHOLD = 0.5f;

    // Adjacency list: neighboring node -> edge distance
    private final Map<Node, Float> neighbors;

    // -------------------------
    //  Constructors
    // -------------------------

    /**
     * Full constructor with custom thresholds.
     *
     * @param id                       unique identifier for this node
     * @param temperature              initial temperature
     * @param gasConcentration         initial gas concentration
     * @param temperatureThreshold     impassable above this temperature
     * @param gasConcentrationThreshold impassable above this gas concentration
     */
    public Node(String id, float temperature, float gasConcentration,
                float temperatureThreshold, float gasConcentrationThreshold) {
        this.id = id;
        this.temperature = temperature;
        this.gasConcentration = gasConcentration;
        this.temperatureThreshold = temperatureThreshold;
        this.gasConcentrationThreshold = gasConcentrationThreshold;
        this.passableOverride = null;
        this.neighbors = new HashMap<>();
    }

    /**
     * Convenience constructor using default environmental thresholds.
     *
     * @param id               unique identifier for this node
     * @param temperature      initial temperature
     * @param gasConcentration initial gas concentration
     */
    public Node(String id, float temperature, float gasConcentration) {
        this(id, temperature, gasConcentration,
                DEFAULT_TEMPERATURE_THRESHOLD,
                DEFAULT_GAS_CONCENTRATION_THRESHOLD);
    }

    // -------------------------
    //  Connection methods
    // -------------------------

    /** Adds a directed edge from this node to the given neighbor. */
    public void addNeighbor(Node neighbor, float distance) {
        if (neighbor == null || distance < 0)
            throw new IllegalArgumentException("Invalid neighbor or distance");
        neighbors.put(neighbor, distance);
    }

    /** Adds an undirected (bidirectional) edge between this node and the given neighbor. */
    public void addBidirectionalNeighbor(Node neighbor, float distance) {
        addNeighbor(neighbor, distance);
        neighbor.addNeighbor(this, distance);
    }

    // -------------------------
    //  Passability
    // -------------------------

    /**
     * Returns whether this node is currently passable.
     *
     * <p>Evaluation order:
     * <ol>
     *   <li>If a manual override has been set via {@link #setPassable(boolean)},
     *       that value is returned immediately.</li>
     *   <li>Otherwise, the node is passable only if both temperature and gas
     *       concentration are within their configured thresholds.</li>
     * </ol>
     *
     * @return true if this node can be traversed
     */
    public boolean isPassable() {
        if (passableOverride != null) return passableOverride;
        return temperature <= temperatureThreshold
                && gasConcentration <= gasConcentrationThreshold;
    }

    /**
     * Manually overrides the passability of this node, bypassing threshold evaluation.
     * Call {@link #clearPassableOverride()} to restore threshold-based behavior.
     *
     * @param passable true to force this node passable, false to force it impassable
     */
    public void setPassable(boolean passable) {
        this.passableOverride = passable;
    }

    /**
     * Removes any manual passability override and restores threshold-based evaluation.
     * After this call, {@link #isPassable()} will reflect environmental conditions again.
     */
    public void clearPassableOverride() {
        this.passableOverride = null;
    }

    /** Returns true if a manual passability override is currently active. */
    public boolean hasPassableOverride() {
        return passableOverride != null;
    }

    // -------------------------
    //  Core pathfinding methods
    // -------------------------

    /**
     * Finds the nearest reachable Exit from this node using Dijkstra's algorithm.
     * Only traverses nodes where {@link #isPassable()} returns true.
     * An Exit is a valid destination only if it is itself passable.
     *
     * @return an Optional containing the nearest passable Exit, or empty if none is reachable
     */
    public Optional<Exit> findNearestExit() {
        PriorityQueue<float[]> pq = new PriorityQueue<>(Comparator.comparingDouble(a -> a[0]));
        Map<Node, Float> dist = new HashMap<>();
        Map<Integer, Node> hashToNode = new HashMap<>();

        dist.put(this, 0f);
        hashToNode.put(System.identityHashCode(this), this);
        pq.offer(new float[]{0f, System.identityHashCode(this)});

        while (!pq.isEmpty()) {
            float[] cur = pq.poll();
            float curDist = cur[0];
            Node curNode = hashToNode.get((int) cur[1]);

            if (curDist > dist.getOrDefault(curNode, Float.MAX_VALUE)) continue;

            // Only accept exits that are currently passable
            if (curNode instanceof Exit && curNode.isPassable()) {
                return Optional.of((Exit) curNode);
            }

            for (Map.Entry<Node, Float> entry : curNode.neighbors.entrySet()) {
                Node next = entry.getKey();
                if (!next.isPassable()) continue;

                float newDist = curDist + entry.getValue();
                if (newDist < dist.getOrDefault(next, Float.MAX_VALUE)) {
                    dist.put(next, newDist);
                    hashToNode.put(System.identityHashCode(next), next);
                    pq.offer(new float[]{newDist, System.identityHashCode(next)});
                }
            }
        }

        return Optional.empty();
    }

    /**
     * Finds the shortest path from this node to the given target using Dijkstra's algorithm.
     * Only traverses nodes where {@link #isPassable()} returns true.
     * The target node is always reachable as a terminal regardless of its passable state.
     *
     * @param target the destination node
     * @return an Optional containing the ordered list of nodes on the shortest path,
     *         or empty if the target is unreachable
     */
    public Optional<List<Node>> shortestPathTo(Node target) {
        PriorityQueue<float[]> pq = new PriorityQueue<>(Comparator.comparingDouble(a -> a[0]));
        Map<Node, Float> dist = new HashMap<>();
        Map<Node, Node> prev = new HashMap<>();
        Map<Integer, Node> hashToNode = new HashMap<>();

        dist.put(this, 0f);
        hashToNode.put(System.identityHashCode(this), this);
        pq.offer(new float[]{0f, System.identityHashCode(this)});

        while (!pq.isEmpty()) {
            float[] cur = pq.poll();
            float curDist = cur[0];
            Node curNode = hashToNode.get((int) cur[1]);

            if (curNode == target) break;
            if (curDist > dist.getOrDefault(curNode, Float.MAX_VALUE)) continue;

            for (Map.Entry<Node, Float> entry : curNode.neighbors.entrySet()) {
                Node next = entry.getKey();

                // Always allow reaching the target; skip all other impassable nodes
                if (!next.isPassable() && next != target) continue;

                float newDist = curDist + entry.getValue();
                if (newDist < dist.getOrDefault(next, Float.MAX_VALUE)) {
                    dist.put(next, newDist);
                    hashToNode.put(System.identityHashCode(next), next);
                    prev.put(next, curNode);
                    pq.offer(new float[]{newDist, System.identityHashCode(next)});
                }
            }
        }

        if (!dist.containsKey(target)) return Optional.empty();

        LinkedList<Node> path = new LinkedList<>();
        for (Node at = target; at != null; at = prev.get(at)) path.addFirst(at);
        return Optional.of(path);
    }

    /**
     * Finds the K shortest simple (loop-free) paths from this node to the given target
     * using Yen's K-Shortest Paths algorithm.
     *
     * <p>Only traverses nodes where {@link #isPassable()} returns true.
     * The target is always allowed as a terminal regardless of its passable state.
     *
     * <p>Time complexity: O(K * V * (E + V log V))
     *
     * @param target the destination node
     * @param k      the maximum number of shortest paths to return
     * @return an ordered list of up to K {@link PathCandidate} objects, shortest first;
     *         may contain fewer than K entries if fewer paths exist
     * @throws IllegalArgumentException if k is less than 1
     */
    public List<PathCandidate> findKShortestPaths(Node target, int k) {
        if (k < 1) throw new IllegalArgumentException("k must be at least 1");

        // A: confirmed k shortest paths, in ascending order of distance
        List<PathCandidate> A = new ArrayList<>();

        // B: candidate paths not yet confirmed, kept sorted by distance
        PriorityQueue<PathCandidate> B = new PriorityQueue<>();

        // Seed A with the single shortest path
        Optional<List<Node>> firstPath = shortestPathTo(target);
        if (firstPath.isEmpty()) return A;
        A.add(new PathCandidate(pathDistance(firstPath.get()), firstPath.get()));

        for (int kIdx = 1; kIdx < k; kIdx++) {
            PathCandidate prevPath = A.get(kIdx - 1);

            for (int spurIdx = 0; spurIdx < prevPath.nodes.size() - 1; spurIdx++) {
                Node spurNode = prevPath.nodes.get(spurIdx);
                List<Node> rootPath = prevPath.nodes.subList(0, spurIdx + 1);

                // Remove edges that would recreate any already-confirmed path
                // sharing the same root prefix as this spur
                Set<String> removedEdges = new HashSet<>();
                for (PathCandidate confirmed : A) {
                    if (confirmed.nodes.size() > spurIdx
                            && confirmed.nodes.subList(0, spurIdx + 1).equals(rootPath)) {
                        Node nextInConfirmed = confirmed.nodes.get(spurIdx + 1);
                        removedEdges.add(edgeKey(spurNode, nextInConfirmed));
                    }
                }

                // Remove all root-path nodes except spurNode to prevent loops
                Set<Node> removedNodes = new HashSet<>(rootPath.subList(0, rootPath.size() - 1));

                Optional<List<Node>> spurPathOpt =
                        dijkstraWithExclusions(spurNode, target, removedEdges, removedNodes);

                if (spurPathOpt.isPresent()) {
                    List<Node> spurPath = spurPathOpt.get();

                    // Concatenate root prefix with spur (avoid duplicating the spur node)
                    List<Node> totalNodes = new ArrayList<>(rootPath);
                    totalNodes.addAll(spurPath.subList(1, spurPath.size()));

                    PathCandidate candidate = new PathCandidate(pathDistance(totalNodes), totalNodes);

                    // Only add if this path is not already queued
                    boolean duplicate = false;
                    for (PathCandidate queued : B) {
                        if (queued.nodes.equals(candidate.nodes)) { duplicate = true; break; }
                    }
                    if (!duplicate) B.add(candidate);
                }
            }

            if (B.isEmpty()) break;
            A.add(B.poll());
        }

        return A;
    }

    // -------------------------
    //  Private helpers
    // -------------------------

    /**
     * Dijkstra's algorithm with specific edges and nodes temporarily excluded.
     * Used internally by {@link #findKShortestPaths}.
     */
    private Optional<List<Node>> dijkstraWithExclusions(
            Node source, Node target,
            Set<String> removedEdges, Set<Node> removedNodes) {

        PriorityQueue<float[]> pq = new PriorityQueue<>(Comparator.comparingDouble(a -> a[0]));
        Map<Node, Float> dist = new HashMap<>();
        Map<Node, Node> prev = new HashMap<>();
        Map<Integer, Node> hashToNode = new HashMap<>();

        dist.put(source, 0f);
        hashToNode.put(System.identityHashCode(source), source);
        pq.offer(new float[]{0f, System.identityHashCode(source)});

        while (!pq.isEmpty()) {
            float[] cur = pq.poll();
            float curDist = cur[0];
            Node curNode = hashToNode.get((int) cur[1]);

            if (curNode == target) break;
            if (curDist > dist.getOrDefault(curNode, Float.MAX_VALUE)) continue;

            for (Map.Entry<Node, Float> entry : curNode.getNeighbors().entrySet()) {
                Node next = entry.getKey();

                if (removedNodes.contains(next)) continue;
                if (removedEdges.contains(edgeKey(curNode, next))) continue;
                if (!next.isPassable() && next != target) continue;

                float newDist = curDist + entry.getValue();
                if (newDist < dist.getOrDefault(next, Float.MAX_VALUE)) {
                    dist.put(next, newDist);
                    hashToNode.put(System.identityHashCode(next), next);
                    prev.put(next, curNode);
                    pq.offer(new float[]{newDist, System.identityHashCode(next)});
                }
            }
        }

        if (!dist.containsKey(target)) return Optional.empty();

        LinkedList<Node> path = new LinkedList<>();
        for (Node at = target; at != null; at = prev.get(at)) path.addFirst(at);
        return Optional.of(path);
    }

    /**
     * Computes the total distance of an ordered path by summing consecutive edge weights.
     * Returns {@link Float#MAX_VALUE} if any edge in the path is missing.
     */
    private float pathDistance(List<Node> path) {
        float total = 0f;
        for (int i = 0; i < path.size() - 1; i++) {
            Float edgeDist = path.get(i).getNeighbors().get(path.get(i + 1));
            if (edgeDist == null) return Float.MAX_VALUE;
            total += edgeDist;
        }
        return total;
    }

    /**
     * Produces a unique string key for a directed edge from {@code from} to {@code to}.
     * Used to identify edges that must be temporarily excluded during Yen's algorithm.
     */
    private String edgeKey(Node from, Node to) {
        return System.identityHashCode(from) + "->" + System.identityHashCode(to);
    }

    // -------------------------
    //  Getters / Setters
    // -------------------------

    public String getId() { return id; }

    public float getTemperature() { return temperature; }

    /**
     * Updates the temperature at this node.
     * If no manual override is active, this may change the result of {@link #isPassable()}.
     */
    public void setTemperature(float temperature) { this.temperature = temperature; }

    public float getGasConcentration() { return gasConcentration; }

    /**
     * Updates the gas concentration at this node.
     * If no manual override is active, this may change the result of {@link #isPassable()}.
     */
    public void setGasConcentration(float gasConcentration) { this.gasConcentration = gasConcentration; }

    public float getTemperatureThreshold() { return temperatureThreshold; }

    /** Sets the temperature threshold above which this node is considered impassable. */
    public void setTemperatureThreshold(float temperatureThreshold) {
        this.temperatureThreshold = temperatureThreshold;
    }

    public float getGasConcentrationThreshold() { return gasConcentrationThreshold; }

    /** Sets the gas concentration threshold above which this node is considered impassable. */
    public void setGasConcentrationThreshold(float gasConcentrationThreshold) {
        this.gasConcentrationThreshold = gasConcentrationThreshold;
    }

    public Map<Node, Float> getNeighbors() { return Collections.unmodifiableMap(neighbors); }

    @Override
    public String toString() {
        String passableSource = passableOverride != null ? "override" : "threshold";
        return String.format("Node{id='%s', passable=%b (%s), temp=%.1f/%.1f, gas=%.2f/%.2f}",
                id, isPassable(), passableSource,
                temperature, temperatureThreshold,
                gasConcentration, gasConcentrationThreshold);
    }
}