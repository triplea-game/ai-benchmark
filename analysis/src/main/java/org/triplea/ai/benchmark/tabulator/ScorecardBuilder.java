package org.triplea.ai.benchmark.tabulator;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.IntStream;
import javax.annotation.Nullable;
import org.triplea.ai.benchmark.tabulator.Scorecard.Box;
import org.triplea.ai.benchmark.tabulator.Scorecard.Cell;
import org.triplea.ai.benchmark.tabulator.Scorecard.Point;
import org.triplea.ai.benchmark.tabulator.Scorecard.Team;

/**
 * Builds a {@link Scorecard} comparing runs, each measured against the first. A kata seat is
 * compared across the runs that played it; a run that did not play it shows no games.
 */
final class ScorecardBuilder {
  private final List<RunData> runs;

  ScorecardBuilder(final List<RunData> runs) {
    if (runs.isEmpty()) {
      throw new IllegalArgumentException("At least one run is needed");
    }
    this.runs = runs;
  }

  Scorecard build() {
    final Map<String, JsonObject> katas = new LinkedHashMap<>();
    final Map<String, List<String>> seats = new LinkedHashMap<>();
    for (final RunData run : runs) {
      for (final JsonObject kata : run.kata()) {
        final String name = kata.get("name").getAsString();
        katas.putIfAbsent(name, kata);
        final List<String> known = seats.computeIfAbsent(name, k -> new ArrayList<>());
        for (final JsonElement seat : kata.getAsJsonArray("seats")) {
          if (!known.contains(seat.getAsString())) {
            known.add(seat.getAsString());
          }
        }
      }
    }
    final List<Box> series = new ArrayList<>();
    katas.forEach((name, kata) -> seats.get(name).forEach(seat -> series.add(box(kata, seat))));
    return new Scorecard(
        RunData.SCHEMA,
        runs.stream().map(RunData::runInfo).toList(),
        roundCapWarnings(katas.keySet()),
        List.copyOf(series));
  }

  /** A kata played to different round caps in different runs (eg thorough versus not). */
  private List<String> roundCapWarnings(final Iterable<String> kataNames) {
    final List<String> warnings = new ArrayList<>();
    for (final String name : kataNames) {
      final Map<Integer, List<Integer>> runsByCap = new TreeMap<>();
      for (int i = 0; i < runs.size(); i++) {
        final Optional<Integer> cap = roundCap(runs.get(i), name);
        if (cap.isPresent()) {
          runsByCap.computeIfAbsent(cap.get(), k -> new ArrayList<>()).add(i + 1);
        }
      }
      if (runsByCap.size() > 1) {
        final List<String> parts = new ArrayList<>();
        runsByCap.forEach((cap, ids) -> parts.add("run " + ids + " " + cap));
        warnings.add(
            "kata "
                + name
                + " was played to different round caps ("
                + String.join(", ", parts)
                + "); results are not comparable");
      }
    }
    return List.copyOf(warnings);
  }

  private static Optional<Integer> roundCap(final RunData run, final String kataName) {
    return run.kata().stream()
        .filter(k -> k.get("name").getAsString().equals(kataName))
        .map(k -> k.getAsJsonObject("limits"))
        .filter(limits -> limits != null && limits.has("rounds"))
        .map(limits -> limits.get("rounds").getAsInt())
        .findFirst();
  }

  private Box box(final JsonObject kata, final String seat) {
    final String name = kata.get("name").getAsString();
    final List<String> metrics =
        kata.getAsJsonArray("metrics").asList().stream().map(JsonElement::getAsString).toList();
    final List<List<JsonObject>> gamesByRun = runs.stream().map(r -> r.games(name, seat)).toList();
    final Reference reference = reference(runs.get(0), gamesByRun.get(0), seat, metrics);
    final List<Team> teams = new ArrayList<>();
    for (int i = 0; i < runs.size(); i++) {
      teams.add(team(runs.get(i), gamesByRun.get(i), seat, metrics, i == 0 ? null : reference));
    }
    return new Box(
        name,
        seat,
        kata.get("reference_ai").getAsString(),
        kata.get("primary_metric").getAsString(),
        metrics,
        List.copyOf(teams),
        DetailBuilder.build(
            IntStream.range(0, runs.size())
                .mapToObj(i -> runs.get(i).stepRecords(gamesByRun.get(i)))
                .toList()));
  }

  /** The first run's per-game values that every later run is measured against. */
  private record Reference(
      List<Double> scores,
      Map<String, List<Double>> finalGaps,
      Map<String, Map<Integer, List<Double>>> gapsByTurn) {}

  private static Reference reference(
      final RunData run,
      final List<JsonObject> seatGames,
      final String seat,
      final List<String> metrics) {
    final List<JsonObject> finals = run.finals(seatGames);
    final List<JsonObject> turns = run.turnSnapshots(seatGames);
    final Map<String, List<Double>> finalGaps = new LinkedHashMap<>();
    final Map<String, Map<Integer, List<Double>>> gapsByTurn = new LinkedHashMap<>();
    for (final String metric : metrics) {
      finalGaps.put(metric, values(finals, metric, "gap"));
      gapsByTurn.put(metric, gapsByTurn(turns, metric));
    }
    return new Reference(scores(finished(seatGames), seat), finalGaps, gapsByTurn);
  }

  private Team team(
      final RunData run,
      final List<JsonObject> seatGames,
      final String seat,
      final List<String> metrics,
      @Nullable final Reference reference) {
    final List<JsonObject> finals = run.finals(seatGames);
    final List<JsonObject> turns = run.turnSnapshots(seatGames);
    final List<JsonObject> finished = finished(seatGames);
    final List<Double> scores = scores(finished, seat);
    final Map<String, Cell> cells = new LinkedHashMap<>();
    final Map<String, Stats.Difference> difference = new LinkedHashMap<>();
    final Map<String, Integer> separatesAt = new LinkedHashMap<>();
    final Map<String, List<Point>> trajectories = new LinkedHashMap<>();
    for (final String metric : metrics) {
      final List<Double> gaps = values(finals, metric, "gap");
      cells.put(
          metric,
          new Cell(
              Stats.summarize(gaps),
              Stats.summarize(values(finals, metric, "seat")),
              Stats.summarize(values(finals, metric, "enemy"))));
      final Map<Integer, List<Double>> byTurn = gapsByTurn(turns, metric);
      if (reference != null) {
        difference.put(metric, Stats.difference(reference.finalGaps().get(metric), gaps));
        separatesAt(reference.gapsByTurn().get(metric), byTurn)
            .ifPresent(turn -> separatesAt.put(metric, turn));
      }
      trajectories.put(
          metric,
          byTurn.entrySet().stream()
              .map(e -> new Point(e.getKey(), Stats.summarize(e.getValue())))
              .toList());
    }
    return new Team(
        run.runInfo().label(),
        finished.size(),
        (int) finished.stream().filter(g -> winnerSides(g).contains(seat)).count(),
        (int)
            finished.stream()
                .filter(g -> !winnerSides(g).isEmpty() && !winnerSides(g).contains(seat))
                .count(),
        (int) finished.stream().filter(g -> g.get("capped").getAsBoolean()).count(),
        (int) seatGames.stream().filter(g -> status(g).equals("crash")).count(),
        (int) seatGames.stream().filter(g -> status(g).equals("timeout")).count(),
        Stats.summarize(scores),
        reference == null ? null : Stats.difference(reference.scores(), scores),
        cells,
        difference,
        separatesAt,
        trajectories);
  }

  private static List<JsonObject> finished(final List<JsonObject> seatGames) {
    return seatGames.stream().filter(g -> status(g).equals("ok")).toList();
  }

  /** A win 1, a loss 0, and a capped or unfinished game ½, from the seat's side. */
  private static List<Double> scores(final List<JsonObject> finished, final String seat) {
    return finished.stream()
        .map(
            g -> {
              final List<String> winners = winnerSides(g);
              if (winners.isEmpty()) {
                return 0.5;
              }
              return winners.contains(seat) ? 1.0 : 0.0;
            })
        .toList();
  }

  /** The first turn index whose gap differs from the reference's with a CI that excludes 0. */
  private static Optional<Integer> separatesAt(
      final Map<Integer, List<Double>> reference, final Map<Integer, List<Double>> byTurn) {
    return byTurn.entrySet().stream()
        .filter(e -> reference.containsKey(e.getKey()))
        .filter(e -> Stats.difference(reference.get(e.getKey()), e.getValue()).excludesZero())
        .map(Map.Entry::getKey)
        .findFirst();
  }

  private static Map<Integer, List<Double>> gapsByTurn(
      final List<JsonObject> turns, final String metric) {
    final Map<Integer, List<Double>> byTurn = new TreeMap<>();
    turns.stream()
        .filter(s -> hasMetric(s, metric))
        .forEach(
            s ->
                byTurn
                    .computeIfAbsent(s.get("turn_index").getAsInt(), t -> new ArrayList<>())
                    .add(metricValue(s, metric, "gap")));
    return byTurn;
  }

  private static List<Double> values(
      final List<JsonObject> snapshots, final String metric, final String field) {
    return snapshots.stream()
        .filter(s -> hasMetric(s, metric))
        .map(s -> metricValue(s, metric, field))
        .toList();
  }

  private static boolean hasMetric(final JsonObject snapshot, final String metric) {
    return snapshot.getAsJsonObject("metrics").has(metric);
  }

  private static double metricValue(
      final JsonObject snapshot, final String metric, final String field) {
    return snapshot.getAsJsonObject("metrics").getAsJsonObject(metric).get(field).getAsDouble();
  }

  private static String status(final JsonObject game) {
    return game.get("status").getAsString();
  }

  private static List<String> winnerSides(final JsonObject game) {
    final JsonElement winner = game.get("winner");
    return winner == null || winner.isJsonNull()
        ? List.of()
        : Arrays.asList(winner.getAsString().split("\\+"));
  }
}
