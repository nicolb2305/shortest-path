package shortestpath.pathfinder;

import java.util.List;

public class ExportPath {
    public final int plane;
    public final List<int[]> path;

    public ExportPath(int plane, List<int[]> path) {
        this.plane = plane;
        this.path = path;
    }
}
