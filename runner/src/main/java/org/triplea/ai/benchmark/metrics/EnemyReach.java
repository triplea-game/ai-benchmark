package org.triplea.ai.benchmark.metrics;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.triplea.ai.pro.ProData;
import games.strategy.triplea.ai.pro.data.ProTerritory;
import games.strategy.triplea.ai.pro.data.ProTerritoryManager;
import games.strategy.triplea.ai.pro.util.ProBattleUtils;
import games.strategy.triplea.delegate.Matches;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The strongest enemy attack on each territory a player holds or has units in, by Pro AI's own
 * reach model: it takes the strongest single enemy player per territory, not combined alliance
 * attacks, and its strength is {@link ProBattleUtils#estimateStrengthDifference}, where 50 is an
 * even fight. Several metrics read it at the same turn end, so the last result per player is cached
 * until the game state moves on.
 */
final class EnemyReach {
  /** An enemy attack likely to win; see the class comment for the scale. */
  static final double TARGET_STRENGTH = 60;

  /** The strongest enemy attack on one territory. */
  record Threat(Set<Unit> attackers, double strength) {
    boolean likelyWins() {
      return strength >= TARGET_STRENGTH;
    }
  }

  private record Key(GameData data, int round, int step, String player) {}

  private static Key cachedKey;
  private static Map<Territory, Threat> cached;

  private EnemyReach() {}

  static synchronized Map<Territory, Threat> of(final GameData data, final GamePlayer player) {
    final Key key =
        new Key(
            data,
            data.getSequence().getRound(),
            data.getSequence().getStepIndex(),
            player.getName());
    if (!key.equals(cachedKey)) {
      cached = compute(data, player);
      cachedKey = key;
    }
    return cached;
  }

  private static Map<Territory, Threat> compute(final GameData data, final GamePlayer player) {
    final var proData = new ProData();
    // The reach model never calls back into the AI, so no live ProAi is needed.
    proData.initializeSimulation(null, data, player);
    final var territoryManager = new ProTerritoryManager(null, proData);
    final List<Territory> held =
        data.getMap().getTerritories().stream()
            .filter(Matches.isTerritoryOwnedBy(player).or(Matches.territoryHasUnitsOwnedBy(player)))
            .toList();
    territoryManager.populateEnemyAttackOptions(List.of(), held);
    final Map<Territory, Threat> threats = new LinkedHashMap<>();
    for (final Territory t : held) {
      final ProTerritory maxAttack = territoryManager.getEnemyAttackOptions().getMax(t);
      if (maxAttack == null) {
        continue;
      }
      final Set<Unit> units = new HashSet<>(maxAttack.getMaxUnits());
      units.addAll(maxAttack.getMaxAmphibUnits());
      if (units.isEmpty()) {
        continue;
      }
      final List<Unit> defenders = t.getUnitCollection().getMatches(Matches.alliedUnit(player));
      threats.put(
          t, new Threat(units, ProBattleUtils.estimateStrengthDifference(t, units, defenders)));
    }
    return threats;
  }
}
