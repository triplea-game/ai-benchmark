package org.triplea.ai.benchmark.runner;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.strategy.engine.data.CompositeChange;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameStep;
import games.strategy.engine.data.Resource;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.engine.data.changefactory.ChangeFactory;
import games.strategy.engine.framework.ServerGame;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.triplea.java.collections.IntegerMap;

/**
 * A human game's position, rebuilt on the kata's map XML from a forum-save export (JSONL: a {@code
 * meta} record with the game options, a {@code position} record per player turn start, {@code step}
 * records). Rebuilding rather than loading the save reaches the 1.x-era saves the current engine
 * can't deserialize. It sets owners, units with hits, transport cargo, unplaced units and
 * resources; tech advances, v3 factory damage and per-unit flags are not in the export.
 */
final class HumanStart {
  private final Kata.Human spec;
  private final JsonObject meta;
  private final JsonObject position;

  private HumanStart(final Kata.Human spec, final JsonObject meta, final JsonObject position) {
    this.spec = spec;
    this.meta = meta;
    this.position = position;
  }

  static HumanStart read(final Kata.Human spec) {
    JsonObject meta = null;
    JsonObject candidate = null;
    JsonObject position = null;
    try (BufferedReader reader = Files.newBufferedReader(spec.export())) {
      String line;
      while (position == null && (line = reader.readLine()) != null) {
        // Most lines are events; skip them before parsing.
        if (!line.startsWith("{\"t\":\"meta\"")
            && !line.startsWith("{\"t\":\"position\"")
            && !line.startsWith("{\"t\":\"step\"")) {
          continue;
        }
        final JsonObject o = JsonParser.parseString(line).getAsJsonObject();
        switch (o.get("t").getAsString()) {
          case "meta" -> meta = o;
          case "position" -> {
            final boolean match =
                o.has("player")
                    && !o.get("player").isJsonNull()
                    && o.get("round").getAsInt() == spec.round()
                    && o.get("player").getAsString().equals(spec.player());
            candidate = match ? o : null;
          }
          case "step" -> {
            // A position that only opens the player's round-1 bid steps is not the turn start.
            if (candidate != null) {
              if (!isBidStep(o.get("step").getAsString())) {
                position = candidate;
              }
              candidate = null;
            }
          }
          default -> {}
        }
      }
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
    if (meta == null || position == null) {
      throw new IllegalArgumentException(
          "No turn start for "
              + spec.player()
              + " in round "
              + spec.round()
              + ": "
              + spec.export());
    }
    return new HumanStart(spec, meta, position);
  }

  private static boolean isBidStep(final String stepName) {
    return stepName.toLowerCase().contains("bid");
  }

  /**
   * The game options the human game was played with that the map also has, by name; bids are left
   * out since the position already holds the bid units.
   */
  Map<String, String> options(final GameData data) {
    final Map<String, String> out = new TreeMap<>();
    final var editable = data.getProperties().getEditablePropertiesByName();
    for (final Map.Entry<String, JsonElement> e : meta.getAsJsonObject("options").entrySet()) {
      if (editable.containsKey(e.getKey()) && !e.getKey().toLowerCase().endsWith(" bid")) {
        out.put(e.getKey(), e.getValue().getAsString());
      }
    }
    return out;
  }

  /**
   * Runs the game-init steps, injects the position, marks the round-1 bid steps as run and jumps to
   * the player's first step of the round. Returns what the map lacks (eg: a unit type), which the
   * position then goes without.
   */
  List<String> apply(final ServerGame game) {
    final GameData data = game.getData();
    while (data.getSequence().getStep().getPlayerId() == null) {
      game.runNextStep();
    }
    final List<String> missing = new ArrayList<>();
    try (GameData.Unlocker ignored = data.acquireWriteLock()) {
      data.performChange(inject(data, missing));
      GameStep first = null;
      for (final GameStep s : data.getSequence()) {
        if (isBidStep(s.getName())) {
          while (s.getMaxRunCount() > 0 && !s.hasReachedMaxRunCount()) {
            s.incrementRunCount();
          }
        } else if (first == null
            && s.getPlayerId() != null
            && spec.player().equals(s.getPlayerId().getName())) {
          first = s;
        }
      }
      if (first == null) {
        throw new IllegalArgumentException("No step for " + spec.player());
      }
      data.getSequence().setRoundAndStep(spec.round(), first.getDisplayName(), first.getPlayerId());
      final GameStep landed = data.getSequence().getStep();
      if (!landed.getName().equals(first.getName())
          || data.getSequence().getRound() != spec.round()) {
        throw new IllegalStateException(
            "setRoundAndStep landed on " + landed.getName() + " instead of " + first.getName());
      }
    }
    return missing;
  }

  private CompositeChange inject(final GameData data, final List<String> missing) {
    final CompositeChange change = new CompositeChange();
    for (final Map.Entry<String, JsonElement> te :
        position.getAsJsonObject("territories").entrySet()) {
      final JsonObject tj = te.getValue().getAsJsonObject();
      if (te.getKey().startsWith("@")) {
        injectPlayer(data, te.getKey().substring(1), tj, change, missing);
      } else {
        injectTerritory(data, te.getKey(), tj, change, missing);
      }
    }
    return change;
  }

  /** An {@code @<player>} entry: the player's unplaced units and resources. */
  private static void injectPlayer(
      final GameData data,
      final String name,
      final JsonObject entry,
      final CompositeChange change,
      final List<String> missing) {
    final GamePlayer p = data.getPlayerList().getPlayerId(name);
    if (p == null) {
      missing.add("player " + name);
      return;
    }
    change.add(ChangeFactory.removeUnits(p, new ArrayList<>(p.getUnitCollection().getUnits())));
    final List<Unit> add = new ArrayList<>();
    for (final Map.Entry<String, JsonElement> ue : entry.getAsJsonObject("u").entrySet()) {
      final String[] k = ue.getKey().split("\\|");
      final int n = ue.getValue().getAsInt();
      if (k[0].equals("res")) {
        final Resource r = data.getResourceList().getResourceOptional(k[1]).orElse(null);
        if (r == null) {
          missing.add("resource " + k[1]);
          continue;
        }
        change.add(ChangeFactory.changeResourcesChange(p, r, n - p.getResources().getQuantity(r)));
      } else {
        final UnitType ut = data.getUnitTypeList().getUnitType(k[1]).orElse(null);
        if (ut == null) {
          missing.add("unit type " + k[1]);
          continue;
        }
        add.addAll(ut.create(n, p));
      }
    }
    change.add(ChangeFactory.addUnits(p, add));
  }

  /** A territory: owner ({@code o}), units by "owner|type[|hitN]" ({@code u}) and {@code cargo}. */
  private static void injectTerritory(
      final GameData data,
      final String name,
      final JsonObject entry,
      final CompositeChange change,
      final List<String> missing) {
    final Territory t = data.getMap().getTerritoryOrNull(name);
    if (t == null) {
      missing.add("territory " + name);
      return;
    }
    final GamePlayer owner =
        entry.get("o").isJsonNull()
            ? data.getPlayerList().getNullPlayer()
            : data.getPlayerList().getPlayerId(entry.get("o").getAsString());
    if (owner == null) {
      missing.add("owner " + entry.get("o") + " of " + name);
    } else if (!owner.equals(t.getOwner())) {
      change.add(ChangeFactory.changeOwner(t, owner));
    }
    change.add(ChangeFactory.removeUnits(t, new ArrayList<>(t.getUnitCollection().getUnits())));
    final List<Unit> add = new ArrayList<>();
    final IntegerMap<Unit> hits = new IntegerMap<>();
    final Map<String, List<Unit>> byKey = new HashMap<>();
    for (final Map.Entry<String, JsonElement> ue : entry.getAsJsonObject("u").entrySet()) {
      final String[] k = ue.getKey().split("\\|");
      final GamePlayer up = data.getPlayerList().getPlayerId(k[0]);
      final UnitType ut = data.getUnitTypeList().getUnitType(k[1]).orElse(null);
      if (up == null || ut == null) {
        missing.add("unit " + ue.getKey() + " in " + name);
        continue;
      }
      final List<Unit> us = ut.create(ue.getValue().getAsInt(), up);
      if (k.length > 2 && k[2].startsWith("hit")) {
        us.forEach(u -> hits.put(u, Integer.parseInt(k[2].substring(3))));
      }
      add.addAll(us);
      byKey.computeIfAbsent(k[0] + "|" + k[1], x -> new ArrayList<>()).addAll(us);
    }
    change.add(ChangeFactory.addUnits(t, add));
    if (!hits.isEmpty()) {
      change.add(ChangeFactory.unitsHit(hits, List.of(t)));
    }
    if (!entry.has("cargo")) {
      return;
    }
    for (final JsonElement ce : entry.getAsJsonArray("cargo")) {
      final JsonObject c = ce.getAsJsonObject();
      final List<Unit> transports = byKey.get(c.get("by").getAsString());
      if (transports == null || transports.isEmpty()) {
        missing.add("transport " + c.get("by") + " in " + name);
        continue;
      }
      final Unit transport = transports.remove(0);
      for (final Map.Entry<String, JsonElement> ue : c.getAsJsonObject("u").entrySet()) {
        final List<Unit> pool = byKey.get(ue.getKey());
        for (int i = 0; i < ue.getValue().getAsInt() && pool != null && !pool.isEmpty(); i++) {
          change.add(
              ChangeFactory.unitPropertyChange(
                  pool.remove(0), transport, Unit.PropertyName.TRANSPORTED_BY));
        }
      }
    }
  }
}
