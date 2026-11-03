package org.triplea.ai.benchmark.runner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class KataParserTest {
  private static final Path KATA_DIR = Path.of("/kata/tiny");
  private final KataParser parser = new KataParser(List.of("Hard", "Fast"), List.of("tuv", "pus"));

  @Test
  void minimalDescriptorGetsDefaults() {
    final Kata kata =
        parser.parse(
            KATA_DIR,
            """
            start: {map: game.xml}
            seats: [Axis]
            limits: {rounds: 3}
            """);

    assertThat(kata.name()).isEqualTo("tiny");
    assertThat(kata.start())
        .isEqualTo(new Kata.Start(Kata.StartKind.MAP, KATA_DIR.resolve("game.xml")));
    assertThat(kata.referenceAi()).isEqualTo("Hard");
    assertThat(kata.limits()).isEqualTo(new Kata.Limits(3, 3, 2, 20, 30, null));
    assertThat(kata.detail()).isFalse();
    assertThat(kata.metrics()).containsExactly("tuv", "pus");
    assertThat(kata.primaryMetric()).isEqualTo("tuv");
    assertThat(kata.props()).isEmpty();
  }

  @Test
  void humanStartAndBidAreReadAsPathsInsideTheKata() {
    final Kata human =
        parser.parse(
            KATA_DIR,
            """
            start: {map: game.xml, human: game.jsonl@3@Germans}
            seats: [Axis]
            limits: {rounds: 5}
            """);
    final Kata bid =
        parser.parse(
            KATA_DIR,
            """
            start: {map: game.xml}
            seats: [Axis]
            limits: {rounds: 5}
            bid: game.bid.json
            """);

    assertThat(human.start().human())
        .isEqualTo(new Kata.Human(KATA_DIR.resolve("game.jsonl"), 3, "Germans"));
    assertThat(bid.bid()).isEqualTo(KATA_DIR.resolve("game.bid.json"));
  }

  @Test
  void scriptsAreReadPerPlayerAndRound() {
    final Kata kata =
        parser.parse(
            KATA_DIR,
            """
            start: {map: game.xml}
            seats: [Allies]
            limits: {rounds: 2}
            scripts: [{player: Germans, round: 1, file: g1.json}]
            """);

    assertThat(kata.scripts())
        .containsExactly(new Kata.Script("Germans", 1, KATA_DIR.resolve("g1.json")));
  }

  @Test
  void twoScriptsForOneTurnAreRejected() {
    assertThatThrownBy(
            () ->
                parser.parse(
                    KATA_DIR,
                    """
                    start: {map: game.xml}
                    seats: [Allies]
                    limits: {rounds: 2}
                    scripts:
                      - {player: Germans, round: 1, file: a.json}
                      - {player: Germans, round: 1, file: b.json}
                    """))
        .hasMessageContaining("two scripts for Germans in round 1");
  }

  @Test
  void humanStartNeedsAMapAndNoBid() {
    assertThatThrownBy(
            () ->
                parser.parse(
                    KATA_DIR,
                    """
                    start: {save: game.tsvg, human: game.jsonl@3@Germans}
                    seats: [Axis]
                    limits: {rounds: 5}
                    """))
        .hasMessageContaining("use map");
    assertThatThrownBy(
            () ->
                parser.parse(
                    KATA_DIR,
                    """
                    start: {map: game.xml, human: game.jsonl@3@Germans}
                    seats: [Axis]
                    limits: {rounds: 5}
                    bid: game.bid.json
                    """))
        .hasMessageContaining("drop bid");
  }

  @Test
  void fullDescriptorIsReadAsWritten() {
    final Kata kata =
        parser.parse(
            KATA_DIR,
            """
            name: renamed
            description: a save start
            start: {save: position.tsvg}
            seats: [Axis, Allies]
            reference_ai: Fast
            limits: {rounds: 5, thorough_rounds: 12, min_games: 4, max_games: 8, game_timeout_minutes: 2}
            primary_metric: pus
            metrics: [pus]
            props: {Low Luck: true, Bid: 9}
            """);

    assertThat(kata.name()).isEqualTo("renamed");
    assertThat(kata.start().kind()).isEqualTo(Kata.StartKind.SAVE);
    assertThat(kata.seats()).containsExactly("Axis", "Allies");
    assertThat(kata.limits()).isEqualTo(new Kata.Limits(5, 12, 4, 8, 2, null));
    assertThat(kata.metrics()).containsExactly("pus");
    assertThat(kata.props()).isEqualTo(Map.of("Low Luck", "true", "Bid", "9"));
  }

  @Test
  void stopAfterTurnsDetailOnUnlessSwitchedOff() {
    final String yaml =
        """
        start: {map: game.xml}
        seats: [Axis]
        limits: {rounds: 1, stop_after: Germans}
        """;

    final Kata focused = parser.parse(KATA_DIR, yaml);
    final Kata quiet = parser.parse(KATA_DIR, yaml + "detail: false\n");

    assertThat(focused.limits().stopAfter()).isEqualTo("Germans");
    assertThat(focused.detail()).isTrue();
    assertThat(quiet.detail()).isFalse();
  }

  @Test
  void unknownKeyIsAnError() {
    assertThatThrownBy(
            () ->
                parser.parse(
                    KATA_DIR,
                    """
                    start: {map: game.xml}
                    seats: [Axis]
                    limits: {rounds: 3, max_game: 4}
                    """))
        .hasMessageContaining("unknown key(s) [max_game] in limits");
  }

  @Test
  void unknownAiIsAnError() {
    assertThatThrownBy(
            () ->
                parser.parse(
                    KATA_DIR,
                    """
                    start: {map: game.xml}
                    seats: [Axis]
                    reference_ai: Hardest
                    limits: {rounds: 3}
                    """))
        .hasMessageContaining("reference_ai: unknown 'Hardest'");
  }

  @Test
  void primaryMetricMustBeAmongTheKataMetrics() {
    assertThatThrownBy(
            () ->
                parser.parse(
                    KATA_DIR,
                    """
                    start: {map: game.xml}
                    seats: [Axis]
                    limits: {rounds: 3}
                    metrics: [pus]
                    """))
        .hasMessageContaining("primary_metric: unknown 'tuv'");
  }

  @Test
  void startNeedsExactlyOneOfMapOrSave() {
    assertThatThrownBy(
            () ->
                parser.parse(
                    KATA_DIR,
                    """
                    start: {map: game.xml, save: position.tsvg}
                    seats: [Axis]
                    limits: {rounds: 3}
                    """))
        .hasMessageContaining("exactly one of map or save");
  }

  @Test
  void startMustStayInsideTheKataFolder() {
    assertThatThrownBy(
            () ->
                parser.parse(
                    KATA_DIR,
                    """
                    start: {map: ../other/game.xml}
                    seats: [Axis]
                    limits: {rounds: 3}
                    """))
        .hasMessageContaining("inside the kata folder");
  }

  @Test
  void thoroughParserPlaysTheThoroughRoundCap() {
    final KataParser thorough =
        new KataParser(List.of("Hard", "Fast"), List.of("tuv", "pus"), true);

    final Kata kata =
        thorough.parse(
            KATA_DIR,
            """
            start: {map: game.xml}
            seats: [Axis]
            limits: {rounds: 8, thorough_rounds: 30}
            """);

    assertThat(kata.limits().rounds()).isEqualTo(30);
  }

  @Test
  void thoroughRoundsBelowRoundsIsAnError() {
    assertThatThrownBy(
            () ->
                parser.parse(
                    KATA_DIR,
                    """
                    start: {map: game.xml}
                    seats: [Axis]
                    limits: {rounds: 8, thorough_rounds: 5}
                    """))
        .hasMessageContaining("thorough_rounds");
  }

  @Test
  void minGamesAboveMaxGamesIsAnError() {
    assertThatThrownBy(
            () ->
                parser.parse(
                    KATA_DIR,
                    """
                    start: {map: game.xml}
                    seats: [Axis]
                    limits: {rounds: 3, min_games: 5, max_games: 4}
                    """))
        .hasMessageContaining("min_games is above limits.max_games");
  }
}
