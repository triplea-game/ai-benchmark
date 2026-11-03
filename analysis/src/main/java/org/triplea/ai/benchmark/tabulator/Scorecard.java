package org.triplea.ai.benchmark.tabulator;

import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import org.triplea.ai.benchmark.tabulator.Stats.Difference;
import org.triplea.ai.benchmark.tabulator.Stats.Summary;

/**
 * The comparison of one or more runs, laid out like a baseball scoreboard: each kata seat is a game
 * ("box score"), each metric an inning, each run a team. The first run is the reference the others
 * are measured against. {@code warnings} name differences between the runs that make a comparison
 * unfair, eg a kata played to different round caps.
 */
record Scorecard(int schema, List<RunInfo> runs, List<String> warnings, List<Box> series) {

  /** Where a run came from. */
  record RunInfo(String dir, String ai, String gitSha, String startedAt) {
    /** The AI and the commit it was built from, enough to tell runs of the same AI apart. */
    String label() {
      return ai + " @ " + gitSha.substring(0, Math.min(7, gitSha.length()));
    }
  }

  /** One kata seat, with one team per run in the order the runs were given. */
  record Box(
      String kata,
      String seat,
      String referenceAi,
      String primaryMetric,
      List<String> metrics,
      List<Team> teams,
      List<StepDetail> details) {}

  /**
   * One run's games on a kata seat. {@code games} counts only games that finished without a crash
   * or timeout. {@code difference} is this team minus the first team and empty for the first team.
   * Gaps are at each game's final snapshot.
   *
   * <p>{@code score} is the seat's game score, a win 1, a loss 0 and anything else ½; {@code
   * scoreDifference} is against the first team and null for it. {@code separatesAt} holds, per
   * metric, the first turn index at which this team's mean gap differs from the first team's with a
   * CI that excludes 0: how soon a change shows, where {@code difference} shows how much.
   */
  record Team(
      String label,
      int games,
      int wins,
      int losses,
      int capped,
      int crashes,
      int timeouts,
      Summary score,
      @Nullable Difference scoreDifference,
      Map<String, Cell> cells,
      Map<String, Difference> difference,
      Map<String, Integer> separatesAt,
      Map<String, List<Point>> trajectories) {}

  record Cell(Summary gap, Summary seat, Summary enemy) {}

  /** Mean gap at a turn index; later turns cover fewer games, so each point carries its n. */
  record Point(int turn, Summary gap) {}

  /**
   * What the seat's players did in one step, across games: the territories where the runs differ or
   * the outcome varied. Only present for kata that record detail.
   */
  record StepDetail(String step, List<TerritoryRow> territories) {}

  /** One territory's tally per run, in the order of {@link Box#teams()}. */
  record TerritoryRow(String territory, List<Tally> tallies) {}

  /**
   * One run's games at a territory after a step: the share ending seat-owned, and mean units on the
   * seat's side (total and by type) and on the enemy side.
   */
  record Tally(
      int n,
      double seatOwned,
      double seatUnits,
      double enemyUnits,
      Map<String, Double> seatUnitsByType) {}
}
