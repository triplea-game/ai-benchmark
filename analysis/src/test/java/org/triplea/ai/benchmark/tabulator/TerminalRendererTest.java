package org.triplea.ai.benchmark.tabulator;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.triplea.ai.benchmark.tabulator.Stats.Difference;
import org.triplea.ai.benchmark.tabulator.Stats.Summary;

class TerminalRendererTest {
  @Test
  void summaryIsOneRowPerSeatOnThePrimaryMetric() {
    final Scorecard.Team first = team("Hard Beta @ aaaaaaa", 4, new Summary(4, 100.0, 10.0), null);
    final Scorecard.Team second =
        team("Hard Beta @ bbbbbbb", 5, new Summary(5, 130.0, 8.0), new Difference(30.0, 12.0));
    final Scorecard card =
        new Scorecard(
            1,
            List.of(
                new Scorecard.RunInfo("a", "Hard Beta", "aaaaaaa111", "t0"),
                new Scorecard.RunInfo("b", "Hard Beta", "bbbbbbb222", "t1")),
            List.of(),
            List.of(
                new Scorecard.Box(
                    "bw42v3-opener",
                    "Axis",
                    "Hard",
                    "tuv",
                    List.of("tuv", "income"),
                    List.of(first, second),
                    List.of())));

    final List<String> lines = TerminalRenderer.summary(card).lines().toList();

    assertThat(lines.get(lines.size() - 1))
        .startsWith("bw42v3-opener")
        .contains("Axis", "tuv", "100.0 ±10.0  n=4", "30.0 ±12.0 *  n=5")
        .doesNotContain("income");
  }

  private static Scorecard.Team team(
      final String label, final int games, final Summary gap, final Difference difference) {
    final Summary none = new Summary(games, null, null);
    return new Scorecard.Team(
        label,
        games,
        0,
        0,
        0,
        0,
        0,
        none,
        null,
        Map.of("tuv", new Scorecard.Cell(gap, none, none)),
        difference == null ? Map.of() : Map.of("tuv", difference),
        Map.of(),
        Map.of());
  }
}
