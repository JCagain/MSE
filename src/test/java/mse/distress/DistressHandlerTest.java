package mse.distress;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DistressHandlerTest {

    @Test
    void handle_addsToRecentEvents() {
        DistressHandler handler = new DistressHandler();
        handler.handle(new DistressRecord("1A", 1, 1, "Main Corridor", 0L));
        assertEquals(1, handler.getRecentEvents().size());
        assertEquals("1A", handler.getRecentEvents().get(0).nodeId);
    }

    @Test
    void handle_newestFirst() {
        DistressHandler handler = new DistressHandler();
        handler.handle(new DistressRecord("1A", 1, 1, "A", 0L));
        handler.handle(new DistressRecord("1B", 2, 1, "B", 0L));
        assertEquals("1B", handler.getRecentEvents().get(0).nodeId);
    }

    @Test
    void handle_capsAt100() {
        DistressHandler handler = new DistressHandler();
        for (int i = 0; i < 101; i++) {
            handler.handle(new DistressRecord("N" + i, i, 1, "loc", 0L));
        }
        assertEquals(100, handler.getRecentEvents().size());
    }
}
