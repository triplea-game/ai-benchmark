package org.triplea.ai.benchmark.metrics;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Unit;
import games.strategy.triplea.delegate.Matches;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Enemy attack power that can reach a target it would likely win (see {@link EnemyReach}): a
 * territory of {@code players} where the strongest enemy attack is likely to win. Each enemy unit
 * counts once, however many targets it reaches. Lower is better: the enemy has fewer units with
 * something worth doing.
 */
final class ExposureMetric implements Metric {
  private final String id;
  private final Predicate<Unit> counted;

  private ExposureMetric(final String id, final Predicate<Unit> counted) {
    this.id = id;
    this.counted = counted;
  }

  static ExposureMetric air() {
    return new ExposureMetric("exposure_air", Matches.unitIsAir());
  }

  static ExposureMetric land() {
    return new ExposureMetric("exposure_land", Matches.unitIsAir().negate());
  }

  @Override
  public String id() {
    return id;
  }

  @Override
  public double value(final GameData data, final Collection<GamePlayer> players) {
    final Set<Unit> attackers = new HashSet<>();
    for (final GamePlayer player : players) {
      EnemyReach.of(data, player).values().stream()
          .filter(EnemyReach.Threat::likelyWins)
          .forEach(threat -> attackers.addAll(threat.attackers()));
    }
    return attackers.stream().filter(counted).mapToInt(ExposureMetric::attackPower).sum();
  }

  private static int attackPower(final Unit unit) {
    final var attachment = unit.getUnitAttachment();
    return attachment.getAttack(unit.getOwner()) * attachment.getAttackRolls(unit.getOwner());
  }
}
