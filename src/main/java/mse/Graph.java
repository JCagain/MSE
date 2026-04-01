package mse;

import java.util.*;

/**
 * Manages a collection of nodes forming a weighted graph.
 * Provides node registration and structural validation.
 */
public class Graph {
    private final Map<String, Node> nodes = new LinkedHashMap<>();

    /** Registers a node in the graph. */
    public void addNode(Node node) {
        nodes.put(node.getId(), node);
    }

    /** Returns the node with the given ID, if present. */
    public Optional<Node> getNode(String id) {
        return Optional.ofNullable(nodes.get(id));
    }

    /**
     * Validates that every registered node has at least one neighbor.
     *
     * @throws IllegalStateException if any node has no connections
     */
    public void validate() {
        for (Node node : nodes.values()) {
            if (node.getNeighbors().isEmpty()) {
                throw new IllegalStateException("Node " + node.getId() + " has no connections!");
            }
        }
        System.out.println("Graph validated successfully — " + nodes.size() + " nodes registered.");
    }

    /** Returns an unmodifiable view of all nodes in the graph. */
    public Collection<Node> getAllNodes() {
        return Collections.unmodifiableCollection(nodes.values());
    }
}