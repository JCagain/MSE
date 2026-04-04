package mse.controller;

import mse.distress.DistressRecord;
import mse.topology.TopologyLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MegaBridgeTest {

    private MegaBridge bridge;

    @BeforeEach
    void setUp() throws IOException {
        TopologyLoader.LoadResult result = TopologyLoader.load(Path.of("sample-topology.json"));
        bridge = new MegaBridge(result.graph, result.nodeJsons, "/dev/null");
    }

    @Test
    void initialNodeStates_matchTopology() {
        assertEquals(4, bridge.getNodeStates().size());
        assertTrue(bridge.getNodeStates().containsKey("1A"));
        assertTrue(bridge.getNodeStates().get("1Exit-A").isExit);
    }

    @Test
    void handleSnapshot_updatesPassability() {
        bridge.handleLine("{\"type\":\"state_snapshot\",\"nodes\":[" +
            "{\"id\":\"1A\",\"passable\":false,\"temp\":80.0,\"co2\":0.5," +
            "\"next_hop\":null,\"dist\":3.4028235E38,\"distress\":false,\"timed_out\":false}]}");
        assertFalse(bridge.getNodeStates().get("1A").isPassable);
    }

    @Test
    void handleSnapshot_updatesSensorAndPath() {
        bridge.handleLine("{\"type\":\"state_snapshot\",\"nodes\":[" +
            "{\"id\":\"1A\",\"passable\":true,\"temp\":45.5,\"co2\":0.3," +
            "\"next_hop\":\"1C\",\"dist\":7.0,\"distress\":false,\"timed_out\":false}]}");
        NodeState s = bridge.getNodeStates().get("1A");
        assertEquals(45.5f, s.temperature,      0.01f);
        assertEquals(0.3f,  s.co2,              0.01f);
        assertEquals("1C",  s.computedNextHopId);
        assertEquals(7.0f,  s.computedDistance, 0.01f);
    }

    @Test
    void handleDistress_addsToRecentEvents() {
        bridge.handleLine("{\"type\":\"distress\",\"node_id\":\"1A\",\"seq\":3," +
            "\"floor\":1,\"location_label\":\"Main Corridor West\",\"timestamp_ms\":1712345678}");
        List<DistressRecord> events = bridge.getRecentDistressEvents();
        assertEquals(1, events.size());
        assertEquals("1A", events.get(0).nodeId);
        assertEquals(3,    events.get(0).seq);
    }

    @Test
    void unknownNodeInSnapshot_silentlyIgnored() {
        assertDoesNotThrow(() -> bridge.handleLine("{\"type\":\"state_snapshot\",\"nodes\":[" +
            "{\"id\":\"UNKNOWN\",\"passable\":true,\"temp\":20.0,\"co2\":0.1," +
            "\"next_hop\":null,\"dist\":0.0,\"distress\":false,\"timed_out\":false}]}"));
    }

    @Test
    void malformedJson_silentlyIgnored() {
        assertDoesNotThrow(() -> bridge.handleLine("not json at all"));
    }
}
