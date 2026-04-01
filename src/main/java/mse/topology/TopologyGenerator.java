package mse.topology;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import mse.topology.TopologyLoader.NeighborJson;
import mse.topology.TopologyLoader.NodeJson;
import mse.topology.TopologyLoader.TopologyJson;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Interactive CLI to build a topology.json file.
 *
 * Commands:
 *   node  — add a node (prompts for id, mac, floor, label, is_exit)
 *   edge  — add a bidirectional edge (prompts for both nodes, weight, directions)
 *   list  — print current topology
 *   save  — validate and save to file
 *   quit  — exit without saving
 */
public class TopologyGenerator {

    public static void main(String[] args) throws IOException {
        Scanner scanner = new Scanner(System.in);
        TopologyJson topology = new TopologyJson();
        topology.nodes = new ArrayList<>();
        Map<String, NodeJson> byId = new LinkedHashMap<>();

        System.out.println("=== MSE Topology Generator ===");
        System.out.println("Commands: node, edge, list, save, quit");

        while (true) {
            System.out.print("> ");
            String line = scanner.nextLine().trim();
            if (line.isEmpty()) continue;

            switch (line) {
                case "node" -> addNode(scanner, topology, byId);
                case "edge" -> addEdge(scanner, byId);
                case "list" -> listTopology(topology);
                case "save" -> { if (saveTopology(scanner, topology)) return; }
                case "quit" -> { System.out.println("Aborted."); return; }
                default -> System.out.println("Unknown command. Use: node, edge, list, save, quit");
            }
        }
    }

    private static void addNode(Scanner sc, TopologyJson topology, Map<String, NodeJson> byId) {
        System.out.print("  node_id: ");
        String id = sc.nextLine().trim();
        if (byId.containsKey(id)) { System.out.println("  Node already exists."); return; }

        System.out.print("  mac_address (e.g. AA:BB:CC:DD:EE:01): ");
        String mac = sc.nextLine().trim();

        System.out.print("  floor: ");
        int floor = Integer.parseInt(sc.nextLine().trim());

        System.out.print("  location_label: ");
        String label = sc.nextLine().trim();

        System.out.print("  is_exit (y/n): ");
        boolean isExit = sc.nextLine().trim().equalsIgnoreCase("y");

        NodeJson nj = new NodeJson();
        nj.nodeId = id; nj.macAddress = mac; nj.floor = floor;
        nj.locationLabel = label; nj.isExit = isExit;
        nj.neighbors = new ArrayList<>();
        topology.nodes.add(nj);
        byId.put(id, nj);
        System.out.println("  Added: " + id);
    }

    private static void addEdge(Scanner sc, Map<String, NodeJson> byId) {
        System.out.print("  from node_id: ");
        String fromId = sc.nextLine().trim();
        System.out.print("  to node_id: ");
        String toId = sc.nextLine().trim();

        if (!byId.containsKey(fromId) || !byId.containsKey(toId)) {
            System.out.println("  One or both nodes not found."); return;
        }

        System.out.print("  edge_weight: ");
        float weight = Float.parseFloat(sc.nextLine().trim());

        System.out.print("  direction from " + fromId + " → " + toId
            + " (left/right/forward/back/up/down): ");
        String dirAtoB = sc.nextLine().trim();

        System.out.print("  direction from " + toId + " → " + fromId + ": ");
        String dirBtoA = sc.nextLine().trim();

        NeighborJson ab = new NeighborJson();
        ab.nodeId = toId; ab.edgeWeight = weight; ab.direction = dirAtoB;
        NeighborJson ba = new NeighborJson();
        ba.nodeId = fromId; ba.edgeWeight = weight; ba.direction = dirBtoA;

        byId.get(fromId).neighbors.add(ab);
        byId.get(toId).neighbors.add(ba);
        System.out.println("  Edge added: " + fromId + " ↔ " + toId + " (weight=" + weight + ")");
    }

    private static void listTopology(TopologyJson topology) {
        System.out.println("  Nodes (" + topology.nodes.size() + "):");
        for (NodeJson n : topology.nodes) {
            System.out.printf("    %-12s  floor=%d  exit=%-5b  neighbors=%d%n",
                n.nodeId, n.floor, n.isExit, n.neighbors.size());
        }
    }

    private static boolean saveTopology(Scanner sc, TopologyJson topology) throws IOException {
        List<String> errors = TopologyValidator.validate(topology);
        if (!errors.isEmpty()) {
            System.out.println("  Cannot save — validation errors:");
            errors.forEach(e -> System.out.println("    " + e));
            return false;
        }
        System.out.print("  Output filename: ");
        String filename = sc.nextLine().trim();
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        Files.writeString(Path.of(filename), gson.toJson(topology));
        System.out.println("  Saved to " + filename);
        return true;
    }
}
