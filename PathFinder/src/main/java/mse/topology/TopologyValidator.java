package mse.topology;

import com.google.gson.Gson;
import mse.topology.TopologyLoader.NeighborJson;
import mse.topology.TopologyLoader.NodeJson;
import mse.topology.TopologyLoader.TopologyJson;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * CLI tool to validate a topology.json file.
 *
 * Checks:
 *   1. No duplicate node_ids.
 *   2. All neighbor node_ids reference nodes that exist in the file.
 *   3. Every edge is symmetric: if A lists B as neighbor, B must list A with the same edge_weight.
 *
 * Exits with code 0 on success, 1 on any violation.
 */
public class TopologyValidator {

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.err.println("Usage: TopologyValidator <topology.json>");
            System.exit(1);
        }

        Gson gson = new Gson();
        TopologyJson topology;
        try (Reader reader = Files.newBufferedReader(Path.of(args[0]))) {
            topology = gson.fromJson(reader, TopologyJson.class);
        }

        List<String> errors = validate(topology);
        if (errors.isEmpty()) {
            System.out.println("OK — topology is valid (" + topology.nodes.size() + " nodes)");
        } else {
            System.err.println("INVALID — " + errors.size() + " violation(s):");
            errors.forEach(e -> System.err.println("  " + e));
            System.exit(1);
        }
    }

    public static List<String> validate(TopologyJson topology) {
        List<String> errors = new ArrayList<>();

        // Build lookup map, checking for duplicates
        Map<String, NodeJson> byId = new LinkedHashMap<>();
        for (NodeJson n : topology.nodes) {
            if (byId.containsKey(n.nodeId)) {
                errors.add("Duplicate node_id: " + n.nodeId);
            }
            byId.put(n.nodeId, n);
        }

        for (NodeJson node : topology.nodes) {
            for (NeighborJson nb : node.neighbors) {
                // Check referenced node exists
                if (!byId.containsKey(nb.nodeId)) {
                    errors.add(node.nodeId + " references unknown neighbor: " + nb.nodeId);
                    continue;
                }

                // Check reverse edge exists with matching weight
                NodeJson peer = byId.get(nb.nodeId);
                Optional<NeighborJson> reverse = peer.neighbors.stream()
                    .filter(r -> r.nodeId.equals(node.nodeId))
                    .findFirst();

                if (reverse.isEmpty()) {
                    errors.add("Missing reverse edge: " + nb.nodeId + " → " + node.nodeId);
                } else if (Math.abs(reverse.get().edgeWeight - nb.edgeWeight) > 0.001f) {
                    errors.add("Edge weight mismatch: " + node.nodeId + "→" + nb.nodeId
                        + " is " + nb.edgeWeight + " but reverse is " + reverse.get().edgeWeight);
                }
            }
        }

        return errors;
    }
}
