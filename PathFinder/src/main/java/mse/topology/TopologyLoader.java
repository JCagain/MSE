package mse.topology;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import mse.Exit;
import mse.Graph;
import mse.Node;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Parses a topology.json file into a Graph of Node/Exit objects.
 *
 * Two-pass loading:
 *   Pass 1 — create all Node/Exit instances and register in Graph.
 *   Pass 2 — add directed neighbor edges (topology.json lists both directions explicitly).
 *
 * Also exposes the raw NodeJson list so callers can access metadata
 * (mac_address, floor, location_label) without re-parsing.
 */
public class TopologyLoader {

    // --- JSON binding classes ---

    public static class TopologyJson {
        public List<NodeJson> nodes;
    }

    public static class NodeJson {
        @SerializedName("node_id")       public String nodeId;
        @SerializedName("mac_address")   public String macAddress;
        public int floor;
        @SerializedName("location_label") public String locationLabel;
        @SerializedName("is_exit")       public boolean isExit;
        public List<NeighborJson> neighbors;
    }

    public static class NeighborJson {
        @SerializedName("node_id")    public String nodeId;
        @SerializedName("edge_weight") public float edgeWeight;
        public String direction;
    }

    // --- Result ---

    public static class LoadResult {
        public final Graph graph;
        public final List<NodeJson> nodeJsons;

        public LoadResult(Graph graph, List<NodeJson> nodeJsons) {
            this.graph = graph;
            this.nodeJsons = nodeJsons;
        }
    }

    // --- Loader ---

    public static LoadResult load(Path topologyFile) throws IOException {
        Gson gson = new Gson();
        try (Reader reader = Files.newBufferedReader(topologyFile)) {
            TopologyJson topology = gson.fromJson(reader, TopologyJson.class);
            return buildGraph(topology);
        }
    }

    private static LoadResult buildGraph(TopologyJson topology) {
        Graph graph = new Graph();

        // Pass 1: create nodes
        for (NodeJson nj : topology.nodes) {
            Node node = nj.isExit ? new Exit(nj.nodeId) : new Node(nj.nodeId, 0f, 0f);
            graph.addNode(node);
        }

        // Pass 2: add directed edges (each side is listed explicitly in topology.json)
        for (NodeJson nj : topology.nodes) {
            Node from = graph.getNode(nj.nodeId).orElseThrow(
                () -> new IllegalStateException("Node not found: " + nj.nodeId));
            for (NeighborJson nb : nj.neighbors) {
                Node to = graph.getNode(nb.nodeId).orElseThrow(
                    () -> new IllegalStateException("Neighbor not found: " + nb.nodeId
                        + " (referenced by " + nj.nodeId + ")"));
                from.addNeighbor(to, nb.edgeWeight);
            }
        }

        return new LoadResult(graph, topology.nodes);
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.err.println("Usage: TopologyLoader <topology.json>");
            System.exit(1);
        }
        LoadResult result = load(Path.of(args[0]));
        System.out.println("Loaded " + result.nodeJsons.size() + " nodes:");
        result.nodeJsons.forEach(n ->
            System.out.printf("  %-12s  floor=%d  exit=%-5b  mac=%s%n",
                n.nodeId, n.floor, n.isExit, n.macAddress));
    }
}
