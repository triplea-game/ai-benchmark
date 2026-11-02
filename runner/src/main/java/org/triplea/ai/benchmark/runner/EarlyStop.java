package org.triplea.ai.benchmark.runner;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.apache.commons.math3.distribution.TDistribution;

/**
 * Stops a kata early once the run already differs from a stored baseline run: on every seat, the
 * mean final gap of the kata's primary metric differs from the baseline's with a 99% Welch CI that
 * excludes 0. The only analysis the runner does, because it has to look at results while games are
 * still being played; the tabulator never depends on it.
 *
 * <p>Checking after every pair is a sequential test, which inflates the false-stop rate over a
 * fixed-sample 95% CI; the 99% level and the kata's {@code min_games} floor keep that in check. A
 * stopped kata is a cheap "clearly different", not a measurement: compare runs with the tabulator.
 */
final class EarlyStop {
  private static final double CONFIDENCE = 0.99;

  /** Baseline final gaps by "kata/seat/metric". */
  private final Map<String, List<Double>> baseline;

  /** This run's final gaps by "kata/seat/metric". */
  private final Map<String, List<Double>> current = new HashMap<>();

  private EarlyStop(final Map<String, List<Double>> baseline) {
    this.baseline = baseline;
  }

  /** Reads the final gaps of every finished game in a run folder's raw data. */
  static EarlyStop against(final Path runDir) {
    final Set<String> finished = new HashSet<>();
    final Map<String, String> seatOfGame = new HashMap<>();
    lines(runDir.resolve("games.jsonl"))
        .map(JsonlWriter::parse)
        .filter(game -> "ok".equals(game.get("status")))
        .forEach(
            game -> {
              finished.add((String) game.get("game"));
              seatOfGame.put((String) game.get("game"), (String) game.get("seat"));
            });
    final Map<String, List<Double>> gaps = new HashMap<>();
    lines(runDir.resolve("snapshots.jsonl"))
        .map(JsonlWriter::parse)
        .filter(s -> Boolean.TRUE.equals(s.get("last")) && finished.contains(s.get("game")))
        .forEach(
            s -> addGaps(gaps, (String) s.get("kata"), seatOfGame.get((String) s.get("game")), s));
    return new EarlyStop(gaps);
  }

  /** Records a finished game's final snapshot. */
  void observe(final String kata, final String seat, final Map<String, Object> finalSnapshot) {
    addGaps(current, kata, seat, finalSnapshot);
  }

  /**
   * Why the kata can stop, or empty to keep playing: every seat has {@code min_games} games (at
   * least 2) in both runs and a CI that excludes 0.
   */
  Optional<String> decided(final Kata kata) {
    final List<String> reasons = new ArrayList<>();
    for (final String seat : kata.seats()) {
      final String key = key(kata.name(), seat, kata.primaryMetric());
      final List<Double> base = baseline.getOrDefault(key, List.of());
      final List<Double> now = current.getOrDefault(key, List.of());
      final int needed = Math.max(2, kata.limits().minGames());
      if (base.size() < needed || now.size() < needed) {
        return Optional.empty();
      }
      final double[] diff = welch(base, now);
      if (Math.abs(diff[0]) <= diff[1]) {
        return Optional.empty();
      }
      reasons.add(String.format("%s %s %+.1f ±%.1f", seat, kata.primaryMetric(), diff[0], diff[1]));
    }
    return Optional.of(String.join(", ", reasons));
  }

  /** Mean of {@code b} minus mean of {@code a}, and its CI half-width. */
  static double[] welch(final List<Double> a, final List<Double> b) {
    final double meanA = mean(a);
    final double meanB = mean(b);
    final double va = variance(a, meanA) / a.size();
    final double vb = variance(b, meanB) / b.size();
    final double se = Math.sqrt(va + vb);
    if (se == 0) {
      return new double[] {meanB - meanA, 0};
    }
    final double df = (va + vb) * (va + vb) / (va * va / (a.size() - 1) + vb * vb / (b.size() - 1));
    final double t = new TDistribution(df).inverseCumulativeProbability(0.5 + CONFIDENCE / 2);
    return new double[] {meanB - meanA, t * se};
  }

  @SuppressWarnings("unchecked")
  private static void addGaps(
      final Map<String, List<Double>> into,
      final String kata,
      final String seat,
      final Map<String, Object> snapshot) {
    final var metrics = (Map<String, Map<String, Object>>) snapshot.get("metrics");
    metrics.forEach(
        (metric, value) ->
            into.computeIfAbsent(key(kata, seat, metric), k -> new ArrayList<>())
                .add(((Number) value.get("gap")).doubleValue()));
  }

  private static String key(final String kata, final String seat, final String metric) {
    return kata + "/" + seat + "/" + metric;
  }

  private static double mean(final List<Double> values) {
    return values.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
  }

  private static double variance(final List<Double> values, final double mean) {
    return values.stream().mapToDouble(v -> (v - mean) * (v - mean)).sum() / (values.size() - 1);
  }

  private static Stream<String> lines(final Path file) {
    try {
      return Files.readAllLines(file).stream().filter(line -> !line.isBlank());
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
