package org.triplea.ai.benchmark.metrics;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.triplea.util.TuvCostsCalculator;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.triplea.java.collections.IntegerMap;

/**
 * What happened between snapshots of one game, per player, counted from the start: unit value put
 * on the map and lost from it, and land captured and lost again. Built by diffing the board at each
 * snapshot, so it needs no engine hooks; the cost is that it sees only the net change between two
 * snapshots (a unit bought and killed in the same turn never shows).
 */
final class GameHistory {
  private record Seen(GamePlayer owner, int cost) {}

  /** A capture still waiting to be held or lost again. */
  private static final class Pending {
    final String territory;
    final GamePlayer capturer;
    int snapshotsLeft;

    Pending(final String territory, final GamePlayer capturer, final int snapshotsLeft) {
      this.territory = territory;
      this.capturer = capturer;
      this.snapshotsLeft = snapshotsLeft;
    }
  }

  private final Map<UUID, Seen> units = new HashMap<>();
  private final Map<String, GamePlayer> owners = new HashMap<>();
  private final List<Pending> pending = new ArrayList<>();
  private final IntegerMap<GamePlayer> bought = new IntegerMap<>();
  private final IntegerMap<GamePlayer> lost = new IntegerMap<>();
  private final IntegerMap<GamePlayer> captures = new IntegerMap<>();
  private final IntegerMap<GamePlayer> recaptured = new IntegerMap<>();
  private String lastKey;

  /** The board the counts start from, so the start position is neither bought nor captured. */
  void start(final GameData data) {
    units.clear();
    owners.clear();
    pending.clear();
    snapshotUnits(data).forEach(units::put);
    data.getMap().getTerritories().stream()
        .filter(t -> !t.isWater())
        .forEach(t -> owners.put(t.getName(), t.getOwner()));
    lastKey = key(data);
  }

  /** Brings the counts up to {@code data}; repeat calls at the same game step change nothing. */
  void update(final GameData data) {
    final String key = key(data);
    if (key.equals(lastKey)) {
      return;
    }
    lastKey = key;
    updateUnits(data);
    updateOwners(data);
  }

  double bought(final Collection<GamePlayer> players) {
    return players.stream().mapToInt(bought::getInt).sum();
  }

  double lost(final Collection<GamePlayer> players) {
    return players.stream().mapToInt(lost::getInt).sum();
  }

  double captures(final Collection<GamePlayer> players) {
    return players.stream().mapToInt(captures::getInt).sum();
  }

  double recaptured(final Collection<GamePlayer> players) {
    return players.stream().mapToInt(recaptured::getInt).sum();
  }

  private void updateUnits(final GameData data) {
    final Map<UUID, Seen> now = snapshotUnits(data);
    for (final Map.Entry<UUID, Seen> e : units.entrySet()) {
      final Seen after = now.get(e.getKey());
      if (after == null || !after.owner().equals(e.getValue().owner())) {
        lost.add(e.getValue().owner(), e.getValue().cost());
      }
    }
    for (final Map.Entry<UUID, Seen> e : now.entrySet()) {
      if (!units.containsKey(e.getKey())) {
        bought.add(e.getValue().owner(), e.getValue().cost());
      }
    }
    units.clear();
    units.putAll(now);
  }

  /**
   * A land territory that changed owner is a capture by its new owner; one that goes to a player
   * not allied with its capturer within a round (a snapshot per player) was lost again.
   */
  private void updateOwners(final GameData data) {
    final Map<String, GamePlayer> now = new HashMap<>();
    data.getMap().getTerritories().stream()
        .filter(t -> !t.isWater())
        .forEach(t -> now.put(t.getName(), t.getOwner()));
    for (final Iterator<Pending> it = pending.iterator(); it.hasNext(); ) {
      final Pending p = it.next();
      final GamePlayer owner = now.get(p.territory);
      if (!owner.equals(p.capturer) && !data.getRelationshipTracker().isAllied(owner, p.capturer)) {
        recaptured.add(p.capturer, 1);
        it.remove();
      } else if (--p.snapshotsLeft <= 0) {
        it.remove();
      }
    }
    final int window = data.getPlayerList().getPlayers().size();
    now.forEach(
        (territory, owner) -> {
          if (!owner.equals(owners.get(territory)) && !owner.isNull()) {
            captures.add(owner, 1);
            pending.add(new Pending(territory, owner, window));
          }
        });
    owners.clear();
    owners.putAll(now);
  }

  private static Map<UUID, Seen> snapshotUnits(final GameData data) {
    final TuvCostsCalculator calculator = new TuvCostsCalculator();
    final Map<GamePlayer, IntegerMap<UnitType>> costs = new HashMap<>();
    final Map<UUID, Seen> seen = new HashMap<>();
    for (final Territory t : data.getMap().getTerritories()) {
      for (final Unit unit : t.getUnitCollection()) {
        final GamePlayer owner = unit.getOwner();
        final int cost =
            costs.computeIfAbsent(owner, calculator::getCostsForTuv).getInt(unit.getType());
        seen.put(unit.getId(), new Seen(owner, cost));
      }
    }
    return seen;
  }

  private static String key(final GameData data) {
    return data.getSequence().getRound() + "/" + data.getSequence().getStepIndex();
  }
}
