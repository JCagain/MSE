package mse.controller;

import mse.distress.DistressRecord;
import mse.topology.TopologyLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EspControllerTest {

    private EspController controller;
    private PrintWriter nullWriter;

    @BeforeEach
    void setUp() throws IOException {
        TopologyLoader.LoadResult result = TopologyLoader.load(Path.of("sample-topology.json"));
        controller = new EspController(result.graph, result.nodeJsons, List.of());
        nullWriter = new PrintWriter(Writer.nullWriter());
    }

    @Test
    void initialNodeStates_matchTopology() {
        assertEquals(4, controller.getNodeStates().size());
        assertTrue(controller.getNodeStates().containsKey("1A"));
        assertTrue(controller.getNodeStates().get("1Exit-A").isExit);
    }

    @Test
    void handleNodeState_updatesSensorValues() {
        controller.handleLine(
            "{\"type\":\"node_state\",\"node_id\":\"1A\",\"temp\":45.5,\"co2\":0.3}",
            nullWriter);
        NodeState s = controller.getNodeStates().get("1A");
        assertEquals(45.5f, s.temperature, 0.01f);
        assertEquals(0.3f,  s.co2,         0.01f);
    }

    @Test
    void handleNodeState_dijkstraComputesNextHop() {
        // Topology: 1A -3-> 1C -4-> 1Exit-A (total 7)  vs  1A -5-> 1B -7-> 1Exit-A (total 12)
        // Shortest path from 1A goes via 1C
        controller.handleLine(
            "{\"type\":\"node_state\",\"node_id\":\"1A\",\"temp\":20.0,\"co2\":0.0}",
            nullWriter);
        NodeState s = controller.getNodeStates().get("1A");
        assertEquals("1C",  s.computedNextHopId);
        assertEquals(7.0f,  s.computedDistance, 0.01f);
    }

    @Test
    void handleNodeState_impassableShortcut_reroutesViaAlternate() {
        // Block 1C (temp above 60°C threshold)
        controller.handleLine(
            "{\"type\":\"node_state\",\"node_id\":\"1C\",\"temp\":90.0,\"co2\":0.9}",
            nullWriter);
        // Now 1A can only reach exit via 1B
        controller.handleLine(
            "{\"type\":\"node_state\",\"node_id\":\"1A\",\"temp\":20.0,\"co2\":0.0}",
            nullWriter);
        assertEquals("1B", controller.getNodeStates().get("1A").computedNextHopId);
    }

    @Test
    void handleDistress_addsToRecentEvents() {
        controller.handleLine(
            "{\"type\":\"distress\",\"node_id\":\"1A\",\"seq\":3," +
            "\"floor\":1,\"location_label\":\"Main Corridor West\",\"timestamp_ms\":1712345678}",
            nullWriter);
        List<DistressRecord> events = controller.getRecentDistressEvents();
        assertEquals(1, events.size());
        assertEquals("1A", events.get(0).nodeId);
        assertEquals(3,    events.get(0).seq);
    }

    @Test
    void unknownNode_silentlyIgnored() {
        assertDoesNotThrow(() -> controller.handleLine(
            "{\"type\":\"node_state\",\"node_id\":\"UNKNOWN\",\"temp\":20.0,\"co2\":0.1}",
            nullWriter));
    }

    @Test
    void malformedJson_silentlyIgnored() {
        assertDoesNotThrow(() -> controller.handleLine("not json at all", nullWriter));
    }
}
