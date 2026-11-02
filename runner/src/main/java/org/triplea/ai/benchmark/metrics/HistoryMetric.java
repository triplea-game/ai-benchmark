package org.triplea.ai.benchmark.metrics;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import java.util.Collection;
import java.util.function.BiFunction;

/** A running total from one game's {@link GameHistory}; every one of a game's shares it. */
final class HistoryMetric implements Metric {
  private final String id;
  private final GameHistory history;
  private final BiFunction<GameHistory, Collection<GamePlayer>, Double> total;

  HistoryMetric(
      final String id,
      final GameHistory history,
      final BiFunction<GameHistory, Collection<GamePlayer>, Double> total) {
    this.id = id;
    this.history = history;
    this.total = total;
  }

  @Override
  public String id() {
    return id;
  }

  @Override
  public void start(final GameData data) {
    history.start(data);
  }

  @Override
  public double value(final GameData data, final Collection<GamePlayer> players) {
    history.update(data);
    return total.apply(history, players);
  }
}
