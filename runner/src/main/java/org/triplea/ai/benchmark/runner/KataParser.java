package org.triplea.ai.benchmark.runner;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import javax.annotation.Nullable;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;

/**
 * Reads a kata folder's {@code kata.yaml}. Unknown keys and unknown AI or metric names are errors,
 * so a typo fails the run instead of silently using a default.
 */
public final class KataParser {
  public static final String DESCRIPTOR = "kata.yaml";

  private static final Set<String> TOP_KEYS =
      Set.of(
          "name",
          "description",
          "start",
          "seats",
          "reference_ai",
          "limits",
          "primary_metric",
          "metrics",
          "props",
          "detail",
          "bid",
          "scripts");
  private static final Set<String> START_KEYS = Set.of("map", "save", "human");
  private static final Set<String> SCRIPT_KEYS = Set.of("player", "round", "file");
  private static final Set<String> LIMIT_KEYS =
      Set.of(
          "rounds",
          "min_games",
          "max_games",
          "budget_minutes",
          "game_timeout_minutes",
          "stop_after");

  private final Collection<String> knownAis;
  private final List<String> knownMetrics;

  /** {@code knownMetrics} is in registry order, which is the default metric order. */
  public KataParser(final Collection<String> knownAis, final List<String> knownMetrics) {
    this.knownAis = knownAis;
    this.knownMetrics = knownMetrics;
  }

  public Kata parse(final Path kataDir) {
    final Path file = kataDir.resolve(DESCRIPTOR);
    try {
      return parse(kataDir, Files.readString(file));
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    } catch (final IllegalArgumentException e) {
      throw new IllegalArgumentException(file + ": " + e.getMessage(), e);
    }
  }

  Kata parse(final Path kataDir, final String yaml) {
    final Map<String, Object> root =
        asMap(new Load(LoadSettings.builder().build()).loadFromString(yaml), "the descriptor");
    requireKnownKeys(root, TOP_KEYS, "top level");
    final Map<String, Object> limits = asMap(require(root, "limits"), "limits");
    requireKnownKeys(limits, LIMIT_KEYS, "limits");
    final Kata.Limits parsedLimits =
        new Kata.Limits(
            positiveInt(require(limits, "rounds"), "limits.rounds"),
            positiveInt(limits.getOrDefault("min_games", 2), "limits.min_games"),
            positiveInt(limits.getOrDefault("max_games", 20), "limits.max_games"),
            positiveInt(limits.getOrDefault("budget_minutes", 20), "limits.budget_minutes"),
            positiveInt(
                limits.getOrDefault("game_timeout_minutes", 30), "limits.game_timeout_minutes"),
            limits.containsKey("stop_after")
                ? string(limits.get("stop_after"), "limits.stop_after")
                : null);
    if (parsedLimits.minGames() > parsedLimits.maxGames()) {
      throw new IllegalArgumentException("limits.min_games is above limits.max_games");
    }
    final List<String> metrics =
        root.containsKey("metrics") ? stringList(root.get("metrics"), "metrics") : knownMetrics;
    metrics.forEach(metric -> requireOneOf(metric, knownMetrics, "metrics"));
    final String primaryMetric =
        string(root.getOrDefault("primary_metric", "tuv"), "primary_metric");
    requireOneOf(primaryMetric, metrics, "primary_metric");
    if (root.containsKey("bid") && asMap(require(root, "start"), "start").containsKey("human")) {
      throw new IllegalArgumentException("a human start already holds its bid; drop bid");
    }
    final List<String> seats = stringList(require(root, "seats"), "seats");
    if (seats.isEmpty() || Set.copyOf(seats).size() != seats.size()) {
      throw new IllegalArgumentException("seats must be a non-empty list without repeats");
    }
    return new Kata(
        string(root.getOrDefault("name", kataDir.getFileName().toString()), "name"),
        string(root.getOrDefault("description", ""), "description"),
        kataDir,
        start(kataDir, asMap(require(root, "start"), "start")),
        seats,
        ai(root.getOrDefault("reference_ai", "Hard"), "reference_ai"),
        parsedLimits,
        primaryMetric,
        metrics,
        props(root.getOrDefault("props", Map.of())),
        detail(root.get("detail"), parsedLimits.stopAfter() != null),
        root.containsKey("bid") ? inside(kataDir, root.get("bid"), "bid") : null,
        scripts(kataDir, root.getOrDefault("scripts", List.of())));
  }

  private static List<Kata.Script> scripts(final Path kataDir, final Object value) {
    if (!(value instanceof List<?> list)) {
      throw new IllegalArgumentException("scripts must be a list");
    }
    final List<Kata.Script> scripts = new ArrayList<>();
    final Set<String> turns = new TreeSet<>();
    for (final Object item : list) {
      final Map<String, Object> entry = asMap(item, "a scripts entry");
      requireKnownKeys(entry, SCRIPT_KEYS, "scripts");
      final Kata.Script script =
          new Kata.Script(
              string(require(entry, "player"), "scripts.player"),
              positiveInt(require(entry, "round"), "scripts.round"),
              inside(kataDir, require(entry, "file"), "scripts.file"));
      if (!turns.add(script.player() + "@" + script.round())) {
        throw new IllegalArgumentException(
            "scripts: two scripts for " + script.player() + " in round " + script.round());
      }
      scripts.add(script);
    }
    return List.copyOf(scripts);
  }

  private static Kata.Start start(final Path kataDir, final Map<String, Object> start) {
    requireKnownKeys(start, START_KEYS, "start");
    final boolean map = start.containsKey("map");
    if (map == start.containsKey("save")) {
      throw new IllegalArgumentException("start needs exactly one of map or save");
    }
    final String key = map ? "map" : "save";
    final Path file = inside(kataDir, start.get(key), "start." + key);
    if (!start.containsKey("human")) {
      return new Kata.Start(map ? Kata.StartKind.MAP : Kata.StartKind.SAVE, file);
    }
    if (!map) {
      throw new IllegalArgumentException(
          "start.human rebuilds a position on a map; use map, not save");
    }
    final String[] spec = string(start.get("human"), "start.human").split("@");
    if (spec.length != 3) {
      throw new IllegalArgumentException("start.human must be <export.jsonl>@<round>@<player>");
    }
    final int round;
    try {
      round = Integer.parseInt(spec[1]);
    } catch (final NumberFormatException e) {
      throw new IllegalArgumentException("start.human round is not a number: " + spec[1], e);
    }
    return new Kata.Start(
        Kata.StartKind.MAP,
        file,
        new Kata.Human(inside(kataDir, spec[0], "start.human"), round, spec[2]));
  }

  /** A path named in the descriptor, which must stay inside the kata folder. */
  private static Path inside(final Path kataDir, final Object value, final String key) {
    final Path file = kataDir.resolve(string(value, key)).normalize();
    if (!file.startsWith(kataDir.normalize())) {
      throw new IllegalArgumentException(key + " must be inside the kata folder");
    }
    return file;
  }

  private String ai(final Object value, final String key) {
    final String name = string(value, key);
    requireOneOf(name, knownAis, key);
    return name;
  }

  /** Detail (per-step territory state and AI logs) defaults on for focused, stop_after runs. */
  private static boolean detail(@Nullable final Object value, final boolean defaultValue) {
    if (value == null) {
      return defaultValue;
    }
    if (!(value instanceof Boolean b)) {
      throw new IllegalArgumentException("detail must be true or false");
    }
    return b;
  }

  private static Map<String, String> props(final Object value) {
    final Map<String, String> props = new TreeMap<>();
    asMap(value, "props").forEach((name, v) -> props.put(name, String.valueOf(v)));
    return props;
  }

  private static Object require(final Map<String, Object> map, final String key) {
    final Object value = map.get(key);
    if (value == null) {
      throw new IllegalArgumentException("missing required key '" + key + "'");
    }
    return value;
  }

  private static void requireKnownKeys(
      final Map<String, Object> map, final Set<String> known, final String where) {
    final Set<String> unknown = new TreeSet<>(map.keySet());
    unknown.removeAll(known);
    if (!unknown.isEmpty()) {
      throw new IllegalArgumentException(
          "unknown key(s) " + unknown + " in " + where + "; allowed: " + new TreeSet<>(known));
    }
  }

  private static void requireOneOf(
      final String value, final Collection<String> allowed, final String key) {
    if (!allowed.contains(value)) {
      throw new IllegalArgumentException(key + ": unknown '" + value + "'; known: " + allowed);
    }
  }

  private static Map<String, Object> asMap(final Object value, final String what) {
    if (!(value instanceof Map<?, ?> map)) {
      throw new IllegalArgumentException(what + " must be a mapping");
    }
    final Map<String, Object> result = new LinkedHashMap<>();
    map.forEach((k, v) -> result.put(String.valueOf(k), v));
    return result;
  }

  private static String string(final Object value, final String key) {
    if (!(value instanceof String s) || s.isBlank() && !key.equals("description")) {
      throw new IllegalArgumentException(key + " must be a non-empty string");
    }
    return s;
  }

  private static List<String> stringList(final Object value, final String key) {
    if (!(value instanceof List<?> list)) {
      throw new IllegalArgumentException(key + " must be a list");
    }
    final List<String> result = new ArrayList<>();
    list.forEach(item -> result.add(string(item, key)));
    return List.copyOf(result);
  }

  private static int positiveInt(final Object value, final String key) {
    if (!(value instanceof Integer i) || i <= 0) {
      throw new IllegalArgumentException(key + " must be a positive integer");
    }
    return i;
  }
}
