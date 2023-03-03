package shortestpath.pathfinder;

import net.runelite.api.coords.WorldPoint;

import java.util.List;

public class PathWithPlaneMapID {
    public final int plane;
    public final int mapID;
    public final List<WorldPoint> path;

    public PathWithPlaneMapID(int plane, int mapID, List<WorldPoint> path) {
        this.plane = plane;
        this.mapID = mapID;
        this.path = path;
    }
}
