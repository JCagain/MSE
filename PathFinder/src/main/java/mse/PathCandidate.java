package mse;

import java.util.List;

/**
 * A value object holding a path (ordered list of nodes) and its total distance.
 * Implements Comparable for use in priority queues (Yen's K-Shortest Paths).
 */
public class PathCandidate implements Comparable<PathCandidate> {
    public final float totalDistance;
    public final List<Node> nodes;

    public PathCandidate(float totalDistance, List<Node> nodes) {
        this.totalDistance = totalDistance;
        this.nodes = nodes;
    }

    @Override
    public int compareTo(PathCandidate o) {
        return Float.compare(this.totalDistance, o.totalDistance);
    }
}
