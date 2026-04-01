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
 * Interactive CLI to build or edit a topology.json file.
 *
 * On startup, prompts for a filename — creates a new file or loads an existing one.
 * Every node/edge addition auto-saves to that file immediately.
 *
 * Commands:
 *   node  — add a new node (prompts for id, mac, floor, label, is_exit)
 *   edge  — add a bidirectional edge between two existing nodes
 *   list  — print all current nodes and their neighbor counts
 *   save  — validate and save manually (also happens automatically after node/edge)
 *   quit  — exit (file is already saved)
 */
public class TopologyGenerator {

    public static void main(String[] args) throws IOException {
        Scanner scanner = new Scanner(System.in);
        TopologyJson topology = new TopologyJson();
        topology.nodes = new ArrayList<>();
        Map<String, NodeJson> byId = new LinkedHashMap<>();

        System.out.println("=== MSE Topology Generator ===");

        // Startup: ask for filename, load if it exists
        String filename = promptFilename(scanner, topology, byId);

        System.out.println();
        System.out.println("Commands:");
        System.out.println("  node  — add a new node");
        System.out.println("  edge  — add a bidirectional edge between two existing nodes");
        System.out.println("  list  — show all nodes and neighbor counts");
        System.out.println("  save  — validate and save (also auto-saves after every node/edge)");
        System.out.println("  quit  — exit (file is already saved)");
        System.out.println();

        while (true) {
            System.out.print("> ");
            String line = scanner.nextLine().trim();
            if (line.isEmpty()) continue;

            switch (line) {
                case "node" -> { if (addNode(scanner, topology, byId)) autoSave(topology, filename); }
                case "edge" -> { if (addEdge(scanner, byId))           autoSave(topology, filename); }
                case "list" -> listTopology(topology);
                case "save" -> saveTopology(topology, filename);
                case "quit" -> { System.out.println("Bye. File: " + filename); return; }
                default     -> System.out.println("  Unknown command. Use: node, edge, list, save, quit");
            }
        }
    }

    // -------------------------------------------------------------------------
    // Startup
    // -------------------------------------------------------------------------

    private static String promptFilename(Scanner sc, TopologyJson topology,
                                         Map<String, NodeJson> byId) throws IOException {
        while (true) {
            System.out.print("Topology file (create new or load existing): ");
            String filename = sc.nextLine().trim();
            if (filename.isEmpty()) { System.out.println("  Filename cannot be empty."); continue; }

            Path path = Path.of(filename);
            if (Files.exists(path)) {
                try {
                    TopologyLoader.LoadResult result = TopologyLoader.load(path);
                    for (NodeJson nj : result.nodeJsons) {
                        topology.nodes.add(nj);
                        byId.put(nj.nodeId, nj);
                    }
                    System.out.println("  Loaded " + topology.nodes.size() + " node(s) from " + filename);
                } catch (Exception e) {
                    System.out.println("  Failed to load file: " + e.getMessage());
                    continue;
                }
            } else {
                System.out.println("  File not found — starting new topology: " + filename);
            }
            return filename;
        }
    }

    // -------------------------------------------------------------------------
    // Commands
    // -------------------------------------------------------------------------

    /** Returns true if a node was successfully added. */
    private static boolean addNode(Scanner sc, TopologyJson topology, Map<String, NodeJson> byId) {
        System.out.print("  node_id: ");
        String id = sc.nextLine().trim();
        if (id.isEmpty())              { System.out.println("  node_id cannot be empty."); return false; }
        if (byId.containsKey(id))      { System.out.println("  Node already exists: " + id); return false; }

        System.out.print("  mac_address (e.g. AA:BB:CC:DD:EE:01): ");
        String mac = sc.nextLine().trim();
        if (mac.isEmpty())             { System.out.println("  mac_address cannot be empty."); return false; }

        System.out.print("  floor: ");
        int floor;
        try { floor = Integer.parseInt(sc.nextLine().trim()); }
        catch (NumberFormatException e) { System.out.println("  Invalid floor — must be an integer."); return false; }

        System.out.print("  location_label: ");
        String label = sc.nextLine().trim();
        if (label.isEmpty())           { System.out.println("  location_label cannot be empty."); return false; }

        System.out.print("  is_exit (y/n): ");
        String exitInput = sc.nextLine().trim();
        if (!exitInput.equalsIgnoreCase("y") && !exitInput.equalsIgnoreCase("n")) {
            System.out.println("  Invalid input — enter y or n."); return false;
        }
        boolean isExit = exitInput.equalsIgnoreCase("y");

        NodeJson nj = new NodeJson();
        nj.nodeId = id; nj.macAddress = mac; nj.floor = floor;
        nj.locationLabel = label; nj.isExit = isExit;
        nj.neighbors = new ArrayList<>();
        topology.nodes.add(nj);
        byId.put(id, nj);
        System.out.println("  Added node: " + id);
        return true;
    }

    /** Returns true if an edge was successfully added. */
    private static boolean addEdge(Scanner sc, Map<String, NodeJson> byId) {
        System.out.print("  from node_id: ");
        String fromId = sc.nextLine().trim();
        System.out.print("  to node_id: ");
        String toId = sc.nextLine().trim();

        if (!byId.containsKey(fromId)) { System.out.println("  Node not found: " + fromId); return false; }
        if (!byId.containsKey(toId))   { System.out.println("  Node not found: " + toId);   return false; }
        if (fromId.equals(toId))       { System.out.println("  Cannot add edge from a node to itself."); return false; }

        // Check for duplicate edge
        for (NeighborJson nb : byId.get(fromId).neighbors) {
            if (nb.nodeId.equals(toId)) {
                System.out.println("  Edge already exists: " + fromId + " ↔ " + toId); return false;
            }
        }

        System.out.print("  edge_weight: ");
        float weight;
        try {
            weight = Float.parseFloat(sc.nextLine().trim());
            if (weight <= 0) { System.out.println("  Edge weight must be positive."); return false; }
        } catch (NumberFormatException e) {
            System.out.println("  Invalid weight — must be a number."); return false;
        }

        System.out.print("  direction from " + fromId + " → " + toId
            + " (left/right/forward/back/up/down): ");
        String dirAtoB = sc.nextLine().trim();
        if (dirAtoB.isEmpty()) { System.out.println("  Direction cannot be empty."); return false; }

        System.out.print("  direction from " + toId + " → " + fromId
            + " (left/right/forward/back/up/down): ");
        String dirBtoA = sc.nextLine().trim();
        if (dirBtoA.isEmpty()) { System.out.println("  Direction cannot be empty."); return false; }

        NeighborJson ab = new NeighborJson();
        ab.nodeId = toId;   ab.edgeWeight = weight; ab.direction = dirAtoB;
        NeighborJson ba = new NeighborJson();
        ba.nodeId = fromId; ba.edgeWeight = weight; ba.direction = dirBtoA;

        byId.get(fromId).neighbors.add(ab);
        byId.get(toId).neighbors.add(ba);
        System.out.println("  Edge added: " + fromId + " ↔ " + toId + " (weight=" + weight + ")");
        return true;
    }

    private static void listTopology(TopologyJson topology) {
        if (topology.nodes.isEmpty()) { System.out.println("  No nodes yet."); return; }
        System.out.println("  Nodes (" + topology.nodes.size() + "):");
        for (NodeJson n : topology.nodes) {
            System.out.printf("    %-12s  floor=%d  exit=%-5b  neighbors=%d%n",
                n.nodeId, n.floor, n.isExit, n.neighbors.size());
        }
    }

    // -------------------------------------------------------------------------
    // Save helpers
    // -------------------------------------------------------------------------

    private static void autoSave(TopologyJson topology, String filename) {
        try {
            writeToDisk(topology, filename);
            System.out.println("  Auto-saved to " + filename);
        } catch (IOException e) {
            System.out.println("  Auto-save failed: " + e.getMessage());
        }
    }

    private static void saveTopology(TopologyJson topology, String filename) {
        List<String> errors = TopologyValidator.validate(topology);
        if (!errors.isEmpty()) {
            System.out.println("  Validation errors:");
            errors.forEach(e -> System.out.println("    " + e));
            return;
        }
        try {
            writeToDisk(topology, filename);
            System.out.println("  Saved to " + filename);
        } catch (IOException e) {
            System.out.println("  Save failed: " + e.getMessage());
        }
    }

    private static void writeToDisk(TopologyJson topology, String filename) throws IOException {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        Files.writeString(Path.of(filename), gson.toJson(topology));
    }
}
