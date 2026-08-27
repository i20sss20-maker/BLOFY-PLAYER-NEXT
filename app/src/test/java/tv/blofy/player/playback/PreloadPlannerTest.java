package tv.blofy.player.playback;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;

public final class PreloadPlannerTest {
    @Test public void prioritizesFocusThenNearestNeighbors() {
        PreloadPlanner planner = new PreloadPlanner();
        List<PreloadPlanner.Candidate<String>> result = planner.around(
                Arrays.asList("a", "b", "c", "d", "e"), 2, 2);
        assertEquals(5, result.size());
        assertEquals("c", result.get(0).value);
        assertEquals("d", result.get(1).value);
        assertEquals("b", result.get(2).value);
        assertEquals("e", result.get(3).value);
        assertEquals("a", result.get(4).value);
    }
}
