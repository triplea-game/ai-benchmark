package org.triplea.ai.benchmark.runner;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/** One benchmark scenario, as resolved from its folder's {@code kata.yaml}. */
public record Kata(
    String name,
    String description,
    Path dir,
    Start start,
    List<String> seats,
    String referenceAi,
    Limits limits,
    String primaryMetric,
    List<String> metrics,
    Map<String, String> props,
    boolean detail,
    @Nullable Path bid,
    List<Script> scripts) {

  /** A player's turn in a round played from a {@link KataScript} file, whichever AI plays it. */
  public record Script(String player, int round, Path file) {}

  /**
   * Where a game begins: exactly one of a game XML or a save, as a path inside the kata folder. A
   * map start may rebuild a human game's position on that map, see {@link HumanStart}.
   */
  public record Start(StartKind kind, Path file, @Nullable Human human) {
    public Start(final StartKind kind, final Path file) {
      this(kind, file, null);
    }
  }

  /** A player's turn start in a forum-save export: {@code <export.jsonl>@<round>@<player>}. */
  public record Human(Path export, int round, String player) {
    String spec(final Path dir) {
      return dir.relativize(export) + "@" + round + "@" + player;
    }
  }

  /** How a kata starts. */
  public enum StartKind {
    MAP,
    SAVE
  }

  /**
   * Run limits; game counts are per seat. {@code stopAfter} names a player: the game ends when that
   * player's first turn ends.
   */
  public record Limits(
      int rounds,
      int minGames,
      int maxGames,
      int budgetMinutes,
      int gameTimeoutMinutes,
      @Nullable String stopAfter) {}

  /** The descriptor as written to {@code run.json}, with paths relative to the kata folder. */
  public Map<String, Object> toJson() {
    final Map<String, Object> json = new LinkedHashMap<>();
    json.put("name", name);
    json.put("description", description);
    final Map<String, Object> startJson = new LinkedHashMap<>();
    startJson.put(start.kind().name().toLowerCase(), dir.relativize(start.file()).toString());
    if (start.human() != null) {
      startJson.put("human", start.human().spec(dir));
    }
    json.put("start", startJson);
    json.put("seats", seats);
    json.put("reference_ai", referenceAi);
    final Map<String, Object> limitsJson = new LinkedHashMap<>();
    limitsJson.put("rounds", limits.rounds());
    limitsJson.put("min_games", limits.minGames());
    limitsJson.put("max_games", limits.maxGames());
    limitsJson.put("budget_minutes", limits.budgetMinutes());
    limitsJson.put("game_timeout_minutes", limits.gameTimeoutMinutes());
    limitsJson.put("stop_after", limits.stopAfter());
    json.put("limits", limitsJson);
    json.put("primary_metric", primaryMetric);
    json.put("metrics", metrics);
    json.put("props", props);
    json.put("detail", detail);
    json.put("bid", bid == null ? null : dir.relativize(bid).toString());
    json.put(
        "scripts",
        scripts.stream()
            .map(
                s ->
                    Map.<String, Object>of(
                        "player", s.player(),
                        "round", s.round(),
                        "file", dir.relativize(s.file()).toString()))
            .toList());
    return json;
  }
}
