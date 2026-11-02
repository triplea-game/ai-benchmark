package org.triplea.ai.benchmark.runner;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EarlyStopTest {
  private static final Kata KATA =
      new KataParser(List.of("Hard"), List.of("tuv"))
          .parse(
              Path.of("/kata/k"),
              """
              start: {map: game.xml}
              seats: [Axis]
              limits: {rounds: 3, min_games: 3}
              """);

  @TempDir Path baselineDir;

  @Test
  void keepsPlayingUntilBothRunsHaveMinGames() throws IOException {
    writeBaseline(0, 1, 2);
    final EarlyStop earlyStop = EarlyStop.against(baselineDir);

    earlyStop.observe("k", "Axis", finalSnapshot(100));
    earlyStop.observe("k", "Axis", finalSnapshot(101));

    assertThat(earlyStop.decided(KATA)).isEmpty();
  }

  @Test
  void stopsOnceTheGapClearlyDiffersFromTheBaseline() throws IOException {
    writeBaseline(0, 1, 2);
    final EarlyStop earlyStop = EarlyStop.against(baselineDir);

    earlyStop.observe("k", "Axis", finalSnapshot(100));
    earlyStop.observe("k", "Axis", finalSnapshot(101));
    earlyStop.observe("k", "Axis", finalSnapshot(102));

    assertThat(earlyStop.decided(KATA)).hasValueSatisfying(r -> assertThat(r).contains("+100.0"));
  }

  @Test
  void keepsPlayingWhileTheGapIsWithinNoise() throws IOException {
    writeBaseline(0, 10, 20);
    final EarlyStop earlyStop = EarlyStop.against(baselineDir);

    earlyStop.observe("k", "Axis", finalSnapshot(1));
    earlyStop.observe("k", "Axis", finalSnapshot(11));
    earlyStop.observe("k", "Axis", finalSnapshot(21));

    assertThat(earlyStop.decided(KATA)).isEmpty();
  }

  private void writeBaseline(final double... gaps) throws IOException {
    final List<String> games = new ArrayList<>();
    final List<String> snapshots = new ArrayList<>();
    for (int i = 0; i < gaps.length; i++) {
      games.add(
          String.format(
              "{\"kata\":\"k\",\"seat\":\"Axis\",\"game\":\"k/Axis/%d\",\"status\":\"ok\"}", i));
      snapshots.add(
          String.format(
              "{\"kata\":\"k\",\"game\":\"k/Axis/%d\",\"last\":true,"
                  + "\"metrics\":{\"tuv\":{\"seat\":0,\"enemy\":0,\"gap\":%s}}}",
              i, gaps[i]));
    }
    Files.write(baselineDir.resolve("games.jsonl"), games);
    Files.write(baselineDir.resolve("snapshots.jsonl"), snapshots);
  }

  private static Map<String, Object> finalSnapshot(final double gap) {
    return JsonlWriter.parse(
        "{\"last\":true,\"metrics\":{\"tuv\":{\"seat\":0,\"enemy\":0,\"gap\":" + gap + "}}}");
  }
}
