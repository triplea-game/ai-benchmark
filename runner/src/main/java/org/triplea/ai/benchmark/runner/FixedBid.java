package org.triplea.ai.benchmark.runner;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Resource;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.UnitType;
import games.strategy.triplea.Constants;
import games.strategy.triplea.delegate.Matches;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * A fixed bid from a kata's {@code bid:} file: extra PUs and placed units, applied to the start
 * position before round 1 without asking any AI, so every game starts from the same bid. Bid units
 * are on the map before the first snapshot, so they count as their side's starting TUV.
 *
 * <p>Schema: {@code {"pus": {"<player>": N}, "placements": [{"player", "territory", "unitType",
 * "count"}], "map", "source", "notes"}}; {@code map}, {@code source} and {@code notes} are free
 * text for the reader.
 */
final class FixedBid {
  private static final Set<String> TOP_KEYS = Set.of("map", "pus", "placements", "source", "notes");
  private static final Set<String> PLACEMENT_KEYS =
      Set.of("player", "territory", "unitType", "count");

  record Placement(String player, String territory, String unitType, int count) {}

  private final Path path;
  private final Map<String, Integer> pus;
  private final List<Placement> placements;

  private FixedBid(
      final Path path, final Map<String, Integer> pus, final List<Placement> placements) {
    this.path = path;
    this.pus = pus;
    this.placements = placements;
  }

  static FixedBid load(final Path path) {
    final JsonObject root;
    try {
      root = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
    for (final String key : root.keySet()) {
      require(TOP_KEYS.contains(key), path + ": unknown key '" + key + "'");
    }
    final Map<String, Integer> pus = new TreeMap<>();
    if (root.has("pus")) {
      for (final Map.Entry<String, JsonElement> e : root.getAsJsonObject("pus").entrySet()) {
        pus.put(e.getKey(), nonNegativeInt(e.getValue(), path + ": pus." + e.getKey()));
      }
    }
    final List<Placement> placements = new ArrayList<>();
    final JsonArray array =
        root.has("placements") ? root.getAsJsonArray("placements") : new JsonArray();
    for (int i = 0; i < array.size(); i++) {
      final JsonObject p = array.get(i).getAsJsonObject();
      final String where = path + ": placements[" + i + "]";
      for (final String key : p.keySet()) {
        require(PLACEMENT_KEYS.contains(key), where + ": unknown key '" + key + "'");
      }
      for (final String key : PLACEMENT_KEYS) {
        require(p.has(key), where + ": missing \"" + key + "\"");
      }
      final int count = nonNegativeInt(p.get("count"), where + ".count");
      require(count > 0, where + ".count must be > 0");
      placements.add(
          new Placement(
              p.get("player").getAsString(),
              p.get("territory").getAsString(),
              p.get("unitType").getAsString(),
              count));
    }
    return new FixedBid(path, pus, placements);
  }

  /**
   * Checks the whole bid against {@code data}, then applies it; nothing changes if any entry is
   * wrong. A map whose own "{@code <player> bid}" option is not 0 is rejected: the AI would also
   * spend that bid in the map's bid steps.
   */
  void apply(final GameData data) {
    final List<String> errors = new ArrayList<>();
    for (final GamePlayer player : data.getPlayerList().getPlayers()) {
      final int engineBid = data.getProperties().get(player.getName() + " bid", 0);
      if (engineBid != 0) {
        errors.add(
            "game option '"
                + player.getName()
                + " bid' is "
                + engineBid
                + "; set it to 0 so the bid isn't also given in the map's bid steps");
      }
    }
    for (final String name : pus.keySet()) {
      if (data.getPlayerList().getPlayerId(name) == null) {
        errors.add("pus: no player " + name);
      }
    }
    for (final Placement p : placements) {
      errors.addAll(check(data, p));
    }
    if (!errors.isEmpty()) {
      throw new IllegalArgumentException(
          "Invalid bid " + path + ":\n  " + String.join("\n  ", errors));
    }
    final Resource pu = data.getResourceList().getResourceOrThrow(Constants.PUS);
    pus.forEach(
        (name, n) -> data.getPlayerList().getPlayerId(name).getResources().addResource(pu, n));
    for (final Placement p : placements) {
      final GamePlayer player = data.getPlayerList().getPlayerId(p.player());
      final UnitType type = data.getUnitTypeList().getUnitType(p.unitType()).orElseThrow();
      data.getMap()
          .getTerritoryOrNull(p.territory())
          .getUnitCollection()
          .addAll(type.create(p.count(), player));
    }
  }

  private static List<String> check(final GameData data, final Placement p) {
    final String where = "placement " + p + ": ";
    final GamePlayer player = data.getPlayerList().getPlayerId(p.player());
    final Territory territory = data.getMap().getTerritoryOrNull(p.territory());
    final UnitType type = data.getUnitTypeList().getUnitType(p.unitType()).orElse(null);
    final List<String> errors = new ArrayList<>();
    if (player == null) {
      errors.add(where + "no player " + p.player());
    }
    if (territory == null) {
      errors.add(where + "no territory " + p.territory());
    }
    if (type == null) {
      errors.add(where + "no unit type " + p.unitType());
    }
    if (!errors.isEmpty()) {
      return errors;
    }
    final boolean sea = Matches.unitTypeIsSea().test(type);
    if (territory.isWater()) {
      if (!sea) {
        errors.add(where + "only sea units go in a sea zone (no carrier-based air)");
      } else if (data.getMap().getNeighbors(territory).stream()
          .noneMatch(t -> !t.isWater() && friendly(data, t.getOwner(), player))) {
        errors.add(where + "sea zone touches no land owned by " + p.player() + " or an ally");
      }
    } else if (sea) {
      errors.add(where + "sea unit on land");
    } else if (!friendly(data, territory.getOwner(), player)) {
      errors.add(
          where + "territory is owned by " + territory.getOwner().getName() + ", not an ally");
    }
    return errors;
  }

  private static boolean friendly(final GameData data, final GamePlayer owner, final GamePlayer p) {
    return owner.equals(p) || (!owner.isNull() && data.getRelationshipTracker().isAllied(owner, p));
  }

  private static int nonNegativeInt(final JsonElement e, final String where) {
    require(e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber(), where + " must be a number");
    final double d = e.getAsDouble();
    require(d >= 0 && d == Math.rint(d), where + " must be a whole number >= 0: " + d);
    return (int) d;
  }

  private static void require(final boolean ok, final String message) {
    if (!ok) {
      throw new IllegalArgumentException(message);
    }
  }
}
