package org.triplea.ai.benchmark.metrics;

import games.strategy.engine.data.GameData;
import games.strategy.engine.stats.IStat;
import games.strategy.engine.stats.ProductionStat;
import games.strategy.engine.stats.ResourceStat;
import games.strategy.engine.stats.VictoryCityStat;
import games.strategy.triplea.Constants;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Every registered metric, in report order. Adding a metric means one class plus one line here.
 * Metrics that count what happens over a game share one {@link GameHistory} per game.
 */
public final class Metrics {
  private Metrics() {}

  private static List<Metric> all(final GameHistory history) {
    return List.of(
        new TuvMetric(),
        new StatMetric("income", data -> new ProductionStat()),
        new StatMetric("pus", Metrics::pus),
        new UnitsMetric(),
        new StatMetric("vc", data -> new VictoryCityStat()),
        ExposureMetric.air(),
        ExposureMetric.land(),
        ThreatMetric.capitalsThreatened(),
        ThreatMetric.transportsExposed(),
        ThreatMetric.navyExposed(),
        new HistoryMetric("bought_tuv", history, GameHistory::bought),
        new HistoryMetric("lost_tuv", history, GameHistory::lost),
        new HistoryMetric("captures", history, GameHistory::captures),
        new HistoryMetric("recaptured", history, GameHistory::recaptured));
  }

  public static List<String> ids() {
    return all(new GameHistory()).stream().map(Metric::id).toList();
  }

  /** Fresh instances of the named metrics for one game, in the order given. */
  public static List<Metric> forGame(final List<String> ids) {
    final Map<String, Metric> byId =
        all(new GameHistory()).stream().collect(Collectors.toMap(Metric::id, Function.identity()));
    return ids.stream().map(id -> lookup(byId, id)).toList();
  }

  /** One metric read outside a game's snapshots, eg: the decisive-lead check's TUV and income. */
  public static Metric byId(final String id) {
    return forGame(List.of(id)).get(0);
  }

  private static Metric lookup(final Map<String, Metric> byId, final String id) {
    final Metric metric = byId.get(id);
    if (metric == null) {
      throw new IllegalArgumentException("Unknown metric '" + id + "'; known: " + ids());
    }
    return metric;
  }

  private static IStat pus(final GameData data) {
    return data.getResourceList()
        .getResourceOptional(Constants.PUS)
        .map(ResourceStat::new)
        .orElseThrow(() -> new IllegalStateException("Map has no PUs; drop 'pus' from metrics"));
  }
}
