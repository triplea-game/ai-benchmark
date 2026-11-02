package org.triplea.ai.benchmark.metrics;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.triplea.attachments.TerritoryAttachment;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.util.TuvCostsCalculator;
import java.util.Collection;
import java.util.Map;
import java.util.function.Predicate;

/**
 * What of a player's the enemy can hit next turn, from {@link EnemyReach}; lower is better. These
 * are the generic forms of the trap kata's criteria (capital exposed, transport exposed, a fleet
 * left in strike range).
 */
final class ThreatMetric implements Metric {
  private interface PerPlayer {
    double value(GameData data, GamePlayer player, Map<Territory, EnemyReach.Threat> threats);
  }

  private final String id;
  private final PerPlayer perPlayer;

  private ThreatMetric(final String id, final PerPlayer perPlayer) {
    this.id = id;
    this.perPlayer = perPlayer;
  }

  /** The player's capitals, held by it, that the strongest enemy attack would likely take. */
  static ThreatMetric capitalsThreatened() {
    return new ThreatMetric(
        "capitals_threatened",
        (data, player, threats) ->
            TerritoryAttachment.getAllCapitals(player, data.getMap()).stream()
                .filter(t -> t.isOwnedBy(player))
                .filter(t -> threats.containsKey(t) && threats.get(t).likelyWins())
                .count());
  }

  /** The player's transports in sea zones an enemy can attack and no own warship guards. */
  static ThreatMetric transportsExposed() {
    return new ThreatMetric(
        "transports_exposed",
        (data, player, threats) ->
            threats.keySet().stream()
                .filter(Territory::isWater)
                .filter(t -> !t.getUnitCollection().anyMatch(warshipOf(player)))
                .mapToLong(
                    t ->
                        t.getUnitCollection()
                            .countMatches(
                                Matches.unitIsOwnedBy(player).and(Matches.unitIsSeaTransport())))
                .sum());
  }

  /** TUV of the player's warships in sea zones where the strongest enemy attack likely wins. */
  static ThreatMetric navyExposed() {
    return new ThreatMetric(
        "navy_exposed",
        (data, player, threats) -> {
          final var costs = new TuvCostsCalculator().getCostsForTuv(player);
          return threats.entrySet().stream()
              .filter(e -> e.getKey().isWater() && e.getValue().likelyWins())
              .flatMap(e -> e.getKey().getUnitCollection().getMatches(warshipOf(player)).stream())
              .map(Unit::getType)
              .mapToInt(costs::getInt)
              .sum();
        });
  }

  private static Predicate<Unit> warshipOf(final GamePlayer player) {
    return Matches.unitIsOwnedBy(player)
        .and(Matches.unitIsSea())
        .and(Matches.unitIsSeaTransport().negate());
  }

  @Override
  public String id() {
    return id;
  }

  @Override
  public double value(final GameData data, final Collection<GamePlayer> players) {
    return players.stream()
        .mapToDouble(p -> perPlayer.value(data, p, EnemyReach.of(data, p)))
        .sum();
  }
}
