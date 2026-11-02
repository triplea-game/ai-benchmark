package org.triplea.ai.benchmark.runner;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.strategy.engine.data.GameState;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import javax.annotation.Nullable;

/**
 * One scripted player turn, read from JSON. Each phase is optional; the player's AI plays any phase
 * the script leaves out. Unit counts are keyed by unit type name.
 *
 * <pre>{@code
 * {
 *   "purchase": {"infantry": 6, "armour": 2},
 *   "combatMoves": [{"from": "Germany", "to": "Poland", "units": {"armour": 2}}],
 *   "nonCombatMoves": [{"from": "Germany", "route": ["Germany", "Western Europe"],
 *                       "units": {"infantry": 2}}],
 *   "aiFinishesMoves": false,
 *   "place": [{"territory": "Germany", "units": {"infantry": 6}}]
 * }
 * }</pre>
 *
 * <p>A move list replaces the AI's moves for that phase, unless {@code aiFinishesMoves} is true, in
 * which case the AI then moves whatever it still wants to. Units the script doesn't place are
 * placed by the AI. A purchase the player can't afford falls back to the AI's own purchase.
 */
final class KataScript {
  private static final Set<String> KEYS =
      Set.of("purchase", "combatMoves", "nonCombatMoves", "aiFinishesMoves", "place");

  @Nullable Map<String, Integer> purchase;
  @Nullable List<Move> combatMoves;
  @Nullable List<Move> nonCombatMoves;
  boolean aiFinishesMoves;
  @Nullable List<Placement> place;

  /** One move: {@code route} (every territory, {@code from} first) or {@code to}. */
  static final class Move {
    String from;
    @Nullable String to;
    Map<String, Integer> units;
    @Nullable List<String> route;
  }

  static final class Placement {
    String territory;
    Map<String, Integer> units;
  }

  static KataScript read(final Path file) {
    final String json;
    try {
      json = Files.readString(file);
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
    final JsonObject root = JsonParser.parseString(json).getAsJsonObject();
    final Set<String> unknown = new TreeSet<>(root.keySet());
    unknown.removeAll(KEYS);
    if (!unknown.isEmpty()) {
      throw new IllegalArgumentException(
          file + ": unknown key(s) " + unknown + "; allowed " + KEYS);
    }
    final KataScript script = new Gson().fromJson(root, KataScript.class);
    script.check(file);
    return script;
  }

  /** Fails on a move or placement missing a required field, before any game starts. */
  private void check(final Path file) {
    final List<Move> moves = new ArrayList<>();
    Optional.ofNullable(combatMoves).ifPresent(moves::addAll);
    Optional.ofNullable(nonCombatMoves).ifPresent(moves::addAll);
    for (final Move move : moves) {
      if (move.from == null || move.units == null || (move.to == null && move.route == null)) {
        throw new IllegalArgumentException(file + ": a move needs from, units, and to or route");
      }
    }
    for (final Placement placement : Optional.ofNullable(place).orElse(List.of())) {
      if (placement.territory == null || placement.units == null) {
        throw new IllegalArgumentException(file + ": a placement needs territory and units");
      }
    }
  }

  /** Every territory and unit type the script names that {@code data} lacks. */
  List<String> unknownNames(final GameState data) {
    final List<String> unknown = new ArrayList<>();
    final List<String> territories = new ArrayList<>();
    final List<String> types = new ArrayList<>();
    Optional.ofNullable(purchase).ifPresent(p -> types.addAll(p.keySet()));
    for (final Move move :
        Stream.concat(
                Optional.ofNullable(combatMoves).orElse(List.of()).stream(),
                Optional.ofNullable(nonCombatMoves).orElse(List.of()).stream())
            .toList()) {
      territories.add(move.from);
      Optional.ofNullable(move.to).ifPresent(territories::add);
      Optional.ofNullable(move.route).ifPresent(territories::addAll);
      types.addAll(move.units.keySet());
    }
    for (final Placement placement : Optional.ofNullable(place).orElse(List.of())) {
      territories.add(placement.territory);
      types.addAll(placement.units.keySet());
    }
    territories.stream()
        .filter(t -> data.getMap().getTerritoryOrNull(t) == null)
        .forEach(t -> unknown.add("territory " + t));
    types.stream()
        .filter(t -> data.getUnitTypeList().getUnitType(t).isEmpty())
        .forEach(t -> unknown.add("unit type " + t));
    return unknown;
  }
}
