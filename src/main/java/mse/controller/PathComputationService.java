package mse.controller;

import mse.Exit;
import mse.Graph;
import mse.Node;

import java.util.*;

/**
 * Multi-source Dijkstra from all passable Exit nodes outward.
 * Produces a next-hop and distance for every node in the graph.
 *
 * Uses double[] for the PQ so System.identityHashCode (int) survives
 * the array round-trip without float precision loss.
 */
public class PathComputationService {

    public record PathResult(String nodeId, String nextHopId, float distance) {}

    private PathComputationService() {}

    public static Map<String, PathResult> compute(Graph graph) {
        PriorityQueue<double[]> pq = new PriorityQueue<>(Comparator.comparingDouble(a -> a[0]));
        Map<Node, Double> dist = new HashMap<>();
        Map<Node, Node> prev = new HashMap<>();   // prev[v] = neighbor of v closer to exit
        Map<Integer, Node> hashToNode = new HashMap<>();

        // Seed: all passable exits at distance 0
        for (Node node : graph.getAllNodes()) {
            if (node instanceof Exit && node.isPassable()) {
                dist.put(node, 0.0);
                hashToNode.put(System.identityHashCode(node), node);
                pq.offer(new double[]{0.0, System.identityHashCode(node)});
            }
        }

        while (!pq.isEmpty()) {
            double[] cur = pq.poll();
            double curDist = cur[0];
            Node curNode = hashToNode.get((int) cur[1]);
            if (curNode == null) continue;

            if (curDist > dist.getOrDefault(curNode, Double.MAX_VALUE)) continue;

            for (Map.Entry<Node, Float> entry : curNode.getNeighbors().entrySet()) {
                Node next = entry.getKey();
                if (!next.isPassable()) continue;

                double newDist = curDist + entry.getValue();
                if (newDist < dist.getOrDefault(next, Double.MAX_VALUE)) {
                    dist.put(next, newDist);
                    prev.put(next, curNode);
                    hashToNode.put(System.identityHashCode(next), next);
                    pq.offer(new double[]{newDist, System.identityHashCode(next)});
                }
            }
        }

        Map<String, PathResult> results = new LinkedHashMap<>();
        for (Node node : graph.getAllNodes()) {
            double d = dist.getOrDefault(node, Double.MAX_VALUE);
            Node nextHopNode = prev.get(node);
            results.put(node.getId(), new PathResult(
                node.getId(),
                nextHopNode != null ? nextHopNode.getId() : null,
                d == Double.MAX_VALUE ? Float.MAX_VALUE : (float) d
            ));
        }
        return results;
    }
}
