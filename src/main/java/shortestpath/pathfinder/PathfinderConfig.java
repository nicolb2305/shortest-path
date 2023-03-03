package shortestpath.pathfinder;

import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import lombok.Getter;
import net.runelite.api.Client;
import net.runelite.api.Constants;
import net.runelite.api.GameState;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;
import shortestpath.ShortestPathConfig;
import shortestpath.ShortestPathPlugin;
import shortestpath.Transport;

import static java.lang.Math.abs;

public class PathfinderConfig {
    private static final WorldArea WILDERNESS_ABOVE_GROUND = new WorldArea(2944, 3523, 448, 448, 0);
    private static final WorldArea WILDERNESS_UNDERGROUND = new WorldArea(2944, 9918, 320, 442, 0);

    @Getter
    private final CollisionMap map;
    @Getter
    private final Map<WorldPoint, List<Transport>> transports;
    private final Client client;
    private final ShortestPathConfig config;
    private final ShortestPathPlugin plugin;

    @Getter
    private Duration calculationCutoff;
    private boolean avoidWilderness;
    private boolean useAgilityShortcuts;
    private boolean useGrappleShortcuts;
    private boolean useBoats;
    private boolean useFairyRings;
    private boolean useTeleports;
    private int agilityLevel;
    private int rangedLevel;
    private int strengthLevel;
    private int prayerLevel;
    private int woodcuttingLevel;
    private Map<Quest, QuestState> questStates = new HashMap<>();

    public PathfinderConfig(CollisionMap map, Map<WorldPoint, List<Transport>> transports, Client client,
                            ShortestPathConfig config, ShortestPathPlugin plugin) {
        this.map = map;
        this.transports = transports;
        this.client = client;
        this.config = config;
        this.plugin = plugin;
        refresh();
    }

    public void refresh() {
        calculationCutoff = Duration.ofMillis(config.calculationCutoff() * Constants.GAME_TICK_LENGTH);
        avoidWilderness = config.avoidWilderness();
        useAgilityShortcuts = config.useAgilityShortcuts();
        useGrappleShortcuts = config.useGrappleShortcuts();
        useBoats = config.useBoats();
        useFairyRings = config.useFairyRings();
        useTeleports = config.useTeleports();

        if (GameState.LOGGED_IN.equals(client.getGameState())) {
            agilityLevel = client.getBoostedSkillLevel(Skill.AGILITY);
            rangedLevel = client.getBoostedSkillLevel(Skill.RANGED);
            strengthLevel = client.getBoostedSkillLevel(Skill.STRENGTH);
            prayerLevel = client.getBoostedSkillLevel(Skill.PRAYER);
            woodcuttingLevel = client.getBoostedSkillLevel(Skill.WOODCUTTING);
            plugin.getClientThread().invokeLater(this::refreshQuests);
        }
    }

    private void refreshQuests() {
        useFairyRings &= !QuestState.NOT_STARTED.equals(Quest.FAIRYTALE_II__CURE_A_QUEEN.getState(client));
        for (Map.Entry<WorldPoint, List<Transport>> entry : transports.entrySet()) {
            for (Transport transport : entry.getValue()) {
                if (transport.isQuestLocked()) {
                    try {
                        questStates.put(transport.getQuest(), transport.getQuest().getState(client));
                    } catch (NullPointerException ignored) {
                    }
                }
            }
        }
    }

    private boolean isInWilderness(WorldPoint p) {
        return WILDERNESS_ABOVE_GROUND.distanceTo(p) == 0 || WILDERNESS_UNDERGROUND.distanceTo(p) == 0;
    }

    public boolean avoidWilderness(WorldPoint position, WorldPoint neighbor, WorldPoint target) {
        return avoidWilderness && !isInWilderness(position) && isInWilderness(neighbor) && !isInWilderness(target);
    }

    public boolean isNear(WorldPoint location) {
        if (plugin.isStartPointSet() || client.getLocalPlayer() == null) {
            return true;
        }
        return config.recalculateDistance() < 0 ||
               client.getLocalPlayer().getWorldLocation().distanceTo2D(location) <= config.recalculateDistance();
    }

    public boolean useTransport(Transport transport) {
        final int transportAgilityLevel = transport.getRequiredLevel(Skill.AGILITY);
        final int transportRangedLevel = transport.getRequiredLevel(Skill.RANGED);
        final int transportStrengthLevel = transport.getRequiredLevel(Skill.STRENGTH);
        final int transportPrayerLevel = transport.getRequiredLevel(Skill.PRAYER);
        final int transportWoodcuttingLevel = transport.getRequiredLevel(Skill.WOODCUTTING);

        final boolean isAgilityShortcut = transport.isAgilityShortcut();
        final boolean isGrappleShortcut = transport.isGrappleShortcut();
        final boolean isBoat = transport.isBoat();
        final boolean isFairyRing = transport.isFairyRing();
        final boolean isTeleport = transport.isTeleport();
        final boolean isCanoe = isBoat && transportWoodcuttingLevel > 1;
        final boolean isPrayerLocked = transportPrayerLevel > 1;
        final boolean isQuestLocked = transport.isQuestLocked();

        if (isAgilityShortcut) {
            if (!useAgilityShortcuts || agilityLevel < transportAgilityLevel) {
                return false;
            }

            if (isGrappleShortcut && (!useGrappleShortcuts || rangedLevel < transportRangedLevel || strengthLevel < transportStrengthLevel)) {
                return false;
            }
        }

        if (isBoat) {
            if (!useBoats) {
                return false;
            }

            if (isCanoe && woodcuttingLevel < transportWoodcuttingLevel) {
                return false;
            }
        }

        if (isFairyRing && !useFairyRings) {
            return false;
        }

        if (isTeleport && !useTeleports) {
            return false;
        }

        if (isPrayerLocked && prayerLevel < transportPrayerLevel) {
            return false;
        }

        if (isQuestLocked && !QuestState.FINISHED.equals(questStates.getOrDefault(transport.getQuest(), QuestState.NOT_STARTED))) {
            return false;
        }

        return true;
    }

    private List<ExportPath> calculatePath(List<WorldPoint> path) {
        List<ExportPath> path_components = new ArrayList<>();
        List<int[]> current_path = new ArrayList<>();

        int plane;
        int diff_x;
        int diff_y;
        boolean new_component = true;

        WorldPoint last = path.get(0);
        int last_last_plane = 0;
        int last_diff_x = 0;
        int last_diff_y = 0;

        for (WorldPoint point : path) {
            diff_x = point.getX() - last.getX();
            diff_y = point.getY() - last.getY();
            plane = point.getPlane();
            if (new_component) {
                current_path = new ArrayList<>();
                int[] coords = {last.getX(), last.getY()};
                current_path.add(coords);
                new_component = false;
            } else {
                new_component = (plane != last.getPlane()) || (abs(diff_x) > 1000) || (abs(diff_y) > 1000);
                if ((diff_x != last_diff_x) || (diff_y != last_diff_y) || new_component) {
                    int[] coords = {last.getX(), last.getY()};
                    current_path.add(coords);
                }
                if (new_component) {
                    path_components.add(new ExportPath(last_last_plane, current_path));
                }
            }
            last_last_plane = last.getPlane();
            last = point;
            last_diff_x = diff_x;
            last_diff_y = diff_y;
        }
        int[] coords = {last.getX(), last.getY()};
        current_path.add(coords);
        path_components.add(new ExportPath(last.getPlane(), current_path));

        return path_components;
    }

    private String createWikiMap(List<ExportPath> calculated_path) {
        StringBuilder out_string = new StringBuilder();
        for (ExportPath path_component : calculated_path) {
            out_string.append("{{Map|");
            for (int[] coordinate : path_component.path) {
                out_string.append(String.format("%d,%d|", coordinate[0], coordinate[1]));
            }
            out_string.append(String.format("mapID=%d|plane=%d|mtype=line}}", config.mapID(), path_component.plane));
        }
        return out_string.toString();
    }

    private String createGeoJson(List<ExportPath> calculated_path) {
        JsonObject geojson = new JsonObject();
        geojson.addProperty("type", "FeatureCollection");
        JsonArray features = new JsonArray();
        for (ExportPath path_component : calculated_path) {
            JsonObject feature = new JsonObject();

            JsonObject properties = new JsonObject();
            Color color = config.stroke();
            String colorHex = String.format("#%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue());
            properties.addProperty("mapID", config.mapID());
            properties.addProperty("plane", path_component.plane);
            properties.addProperty("stroke", colorHex);
            properties.addProperty("stroke-width", config.width());
            properties.addProperty("stroke-opacity", color.getAlpha()/(float)255);
            if (!config.title().equals("")) {
                properties.addProperty("title", config.title());
            }

            JsonObject geometry = new JsonObject();
            JsonArray coordinates = new JsonArray();
            for (int[] coordinate : path_component.path) {
                JsonArray coordinate_array = new JsonArray(2);
                coordinate_array.add(coordinate[0]);
                coordinate_array.add(coordinate[1]);
                coordinates.add(coordinate_array);
            }
            geometry.addProperty("type", "LineString");
            geometry.add("coordinates", coordinates);

            feature.addProperty("type", "Feature");
            feature.add("properties", properties);
            feature.add("geometry", geometry);

            features.add(feature);
        }
        geojson.add("features", features);

        return geojson.toString();
    }

    public void exportPathToClipboard(List<WorldPoint> path) {
        if (!config.exportPathToClipboard()) {
            return;
        }

        List<ExportPath> calculated_path = calculatePath(path);
        String out_string;
        switch (config.exportFormat()) {
            case wiki: out_string = createWikiMap(calculated_path);
                break;
            case geo_json: out_string = createGeoJson(calculated_path);
                break;
            default: out_string = "";
        }

        StringSelection stringSelection = new StringSelection(out_string);
        Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
        clipboard.setContents(stringSelection, null);
    }
}
