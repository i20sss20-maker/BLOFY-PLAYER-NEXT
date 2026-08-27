package tv.blofy.player.playback;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Plans metadata/manifest preloads only. It never buffers video bytes or owns a player. */
public final class PreloadPlanner {
    public static final class Candidate<T> {
        public final T value;
        public final int distance;
        Candidate(T value, int distance) { this.value = value; this.distance = distance; }
    }

    public <T> List<Candidate<T>> around(List<T> items, int focusedIndex, int radius) {
        if (items == null || items.isEmpty() || focusedIndex < 0 || focusedIndex >= items.size()) {
            return Collections.emptyList();
        }
        int safeRadius = Math.max(0, Math.min(radius, 4));
        List<Candidate<T>> result = new ArrayList<>();
        for (int distance = 0; distance <= safeRadius; distance++) {
            if (distance == 0) {
                result.add(new Candidate<>(items.get(focusedIndex), 0));
                continue;
            }
            int next = focusedIndex + distance;
            int previous = focusedIndex - distance;
            if (next < items.size()) result.add(new Candidate<>(items.get(next), distance));
            if (previous >= 0) result.add(new Candidate<>(items.get(previous), distance));
        }
        return result;
    }
}
