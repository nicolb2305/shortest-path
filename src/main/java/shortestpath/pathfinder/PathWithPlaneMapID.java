package shortestpath.pathfinder;

import net.runelite.api.coords.WorldPoint;

import java.util.List;

public class PathWithPlaneMapID {
    public final int plane;
    public final int mapID;
    public final boolean transport;
    public final List<WorldPoint> path;

    public PathWithPlaneMapID(int plane, int mapID, boolean transport, List<WorldPoint> path) {
        this.plane = plane;
        this.mapID = mapID;
        this.transport = transport;
        this.path = path;
    }
}
