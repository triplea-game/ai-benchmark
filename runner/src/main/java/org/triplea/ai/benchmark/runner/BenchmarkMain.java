package org.triplea.ai.benchmark.runner;

import com.google.gson.GsonBuilder;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameStep;
import games.strategy.engine.framework.ServerGame;
import games.strategy.engine.framework.startup.ui.PlayerTypes;
import games.strategy.triplea.delegate.AbstractEndTurnDelegate;
import games.strategy.triplea.delegate.BidPlaceDelegate;
import games.strategy.triplea.delegate.BidPurchaseDelegate;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.function.Consumer;
import org.triplea.ai.benchmark.metrics.Metric;
import org.triplea.ai.benchmark.metrics.Metrics;

/**
 * Plays one AI on each kata, seat by seat, and writes the raw data to a new run folder. It never
 * analyses the data, or compares runs; the tabulator does.
 *
 * <p>Args: {@code --kata-dir <dir> (--list | --out <dir> --ai <name> --kata <glob> [--workers <n>]
 * [--minutes <n>] [--thorough true] [--baseline <run dir>] [--triplea <dir>])}. No pair starts
 * after {@code --minutes} (0: no deadline); {@code --thorough} plays each kata's {@code
 * thorough_rounds}; a baseline run lets {@link EarlyStop} end a kata early. {@link WorkerPool}
 * starts this same class with {@code --play} for each game.
 */
public final class BenchmarkMain {
  /** System property naming the frozen build folder this JVM runs from, if any. */
  private static final String FROZEN_BUILD = "ai.benchmark.build";

  private BenchmarkMain() {}

  public static void main(final String[] args) throws IOException {
    final Map<String, String> opts = parseArgs(args);
    final KataCatalog catalog =
        new KataCatalog(
            Path.of(require(opts, "kata-dir")),
            new KataParser(AiRegistry.names(), Metrics.ids(), thorough(opts)));
    if (opts.containsKey("list")) {
      catalog.all().forEach(kata -> System.out.println(describe(kata)));
      return;
    }
    if (opts.containsKey("play")) {
      playWorker(catalog, opts);
      // Engine threads (battle calculator workers and others) are not all daemons.
      System.exit(0);
    }
    final String ai = require(opts, "ai");
    AiRegistry.type(ai);
    final List<Kata> katas = catalog.matching(require(opts, "kata"));
    if (katas.isEmpty()) {
      System.out.println("No kata match '" + opts.get("kata") + "'; the scorecard will be empty");
    }
    // Fail on a bad start file or seat name before any game runs.
    for (final Kata kata : katas) {
      final GameData data = HeadlessEngine.load(kata);
      kata.seats()
          .forEach(seat -> scriptedPlayers(kata, data, Sides.of(data, seat), ai, player -> {}));
    }
    final String baseline = opts.getOrDefault("baseline", "");
    final EarlyStop earlyStop = baseline.isEmpty() ? null : EarlyStop.against(Path.of(baseline));
    final Map<String, Object> source = source(opts.getOrDefault("triplea", "../triplea"));
    final Path runDir = newRunDir(Path.of(require(opts, "out")), ai, source);
    final int requested = Integer.parseInt(opts.getOrDefault("workers", "0"));
    final int workers = requested > 0 ? requested : WorkerPool.defaultWorkers();
    System.out.printf(
        "WORKERS %d%s%n", workers, requested > 0 ? "" : " (default; override with workers=N)");
    final Map<String, Object> run = new LinkedHashMap<>();
    run.put("schema", 1);
    run.put("ai", ai);
    run.putAll(source);
    run.put("workers", workers);
    final int minutes = Integer.parseInt(opts.getOrDefault("minutes", "20"));
    run.put("minutes", minutes);
    run.put("thorough", thorough(opts));
    run.put("java", System.getProperty("java.version"));
    run.put("cpus", Runtime.getRuntime().availableProcessors());
    run.put("started_at", Instant.now().toString());
    run.put("ended_at", null);
    run.put("baseline", baseline.isEmpty() ? null : baseline);
    run.put("kata", katas.stream().map(Kata::toJson).toList());
    writeRunJson(runDir, run);
    try (JsonlWriter snapshots = new JsonlWriter(runDir.resolve("snapshots.jsonl"));
        JsonlWriter steps = new JsonlWriter(runDir.resolve("steps.jsonl"));
        JsonlWriter games = new JsonlWriter(runDir.resolve("games.jsonl"))) {
      new WorkerPool(
              Path.of(require(opts, "kata-dir")),
              runDir,
              ai,
              workers,
              katas,
              new WorkerPool.Writers(snapshots, steps, games),
              earlyStop,
              minutes == 0 ? null : Duration.ofMinutes(minutes),
              thorough(opts))
          .run();
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted waiting for workers", e);
    }
    run.put("ended_at", Instant.now().toString());
    writeRunJson(runDir, run);
    System.out.println("RUN " + runDir);
    System.exit(0);
  }

  /** Worker mode: plays the one game named in {@code opts}, writing its data to the part folder. */
  private static void playWorker(final KataCatalog catalog, final Map<String, String> opts)
      throws IOException {
    final String name = require(opts, "play");
    final Kata kata =
        catalog.all().stream()
            .filter(k -> k.name().equals(name))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("No kata named " + name));
    final Path partDir = Path.of(require(opts, "part-dir"));
    HeadlessEngine.bootstrap();
    try (JsonlWriter snapshots = new JsonlWriter(partDir.resolve("snapshots.jsonl"));
        JsonlWriter steps = new JsonlWriter(partDir.resolve("steps.jsonl"));
        JsonlWriter games = new JsonlWriter(partDir.resolve("games.jsonl"))) {
      final var sinks = new Sinks(Path.of(require(opts, "run-dir")), snapshots, steps, games);
      final int pair = Integer.parseInt(require(opts, "pair"));
      games.write(playOne(kata, require(opts, "seat"), require(opts, "ai"), pair, sinks));
    }
  }

  /** The fields that identify a game in {@code games.jsonl}. */
  static Map<String, Object> gameRecord(
      final Kata kata, final String seat, final String seatAi, final int pair) {
    final Map<String, Object> record = new LinkedHashMap<>();
    record.put("kata", kata.name());
    record.put("seat", seat);
    record.put("pair", pair);
    record.put("game", gameId(kata, seat, pair));
    record.put("ai", seatAi);
    record.put("reference_ai", kata.referenceAi());
    record.put("seed", null);
    return record;
  }

  static void putCrash(final Map<String, Object> record, final String error) {
    record.put("status", "crash");
    record.put("winner", null);
    record.put("winners", List.of());
    record.put("rounds", null);
    record.put("capped", null);
    record.put("stopped", null);
    record.put("end", null);
    record.put("error", error);
  }

  static void printGame(final Map<String, Object> record) {
    System.out.printf(
        "GAME %s ai=%s status=%s winner=%s rounds=%s capped=%s%n",
        record.get("game"),
        record.get("ai"),
        record.get("status"),
        record.get("winner"),
        record.get("rounds"),
        record.get("capped"));
  }

  private static String gameId(final Kata kata, final String seat, final int pair) {
    return kata.name() + "/" + seat + "/" + pair;
  }

  /** Plays one game, writing its snapshots, and returns its {@code games.jsonl} record. */
  private static Map<String, Object> playOne(
      final Kata kata, final String seat, final String seatAi, final int pair, final Sinks sinks) {
    final String gameId = gameId(kata, seat, pair);
    final Map<String, Object> record = gameRecord(kata, seat, seatAi, pair);
    final long wallStart = System.nanoTime();
    final long cpuStart = processCpuNanos();
    ServerGame game = null;
    try (GameLog log = GameLog.open(kata.detail(), sinks.runDir(), gameId)) {
      final GameData data = HeadlessEngine.load(kata);
      final Sides sides = Sides.of(data, seat);
      final List<ScriptedAi> scripted = new ArrayList<>();
      game =
          HeadlessEngine.newGame(
              data,
              sides.seat(),
              AiRegistry.type(seatAi),
              AiRegistry.type(kata.referenceAi()),
              scriptedPlayers(kata, data, sides, seatAi, scripted::add));
      if (kata.start().human() != null) {
        record.put("human_missing", HumanStart.read(kata.start().human()).apply(game));
      }
      final List<Metric> metrics = Metrics.forGame(kata.metrics());
      metrics.forEach(metric -> metric.start(data));
      final GameDriver.Outcome outcome =
          GameDriver.play(
              game,
              kata.limits().rounds(),
              Duration.ofMinutes(kata.limits().gameTimeoutMinutes()),
              kata.limits().stopAfter(),
              new GameDriver.TurnListener() {
                @Override
                public void onStep(final GameData state, final int round, final GameStep step) {
                  if (!kata.detail() || !isSeatDetailStep(step, sides)) {
                    return;
                  }
                  log.header(round, step.getName());
                  final Map<String, Object> record = new LinkedHashMap<>();
                  record.put("kata", kata.name());
                  record.put("seat", seat);
                  record.put("pair", pair);
                  record.put("game", gameId);
                  record.put("round", round);
                  record.put("player", step.getPlayerId().getName());
                  record.put("step", step.getName());
                  record.put("territories", TerritoryState.capture(state, sides.seat()));
                  sinks.steps().write(record);
                }

                @Override
                public void onSnapshot(
                    final GameData state,
                    final int round,
                    final GamePlayer player,
                    final int turnIndex,
                    final boolean last) {
                  final Map<String, Object> snapshot = new LinkedHashMap<>();
                  snapshot.put("kata", kata.name());
                  snapshot.put("seat", seat);
                  snapshot.put("pair", pair);
                  snapshot.put("game", gameId);
                  snapshot.put("round", round);
                  snapshot.put("player", player == null ? null : player.getName());
                  snapshot.put("turn_index", turnIndex);
                  snapshot.put("last", last);
                  snapshot.put("metrics", measure(state, sides, metrics));
                  sinks.snapshots().write(snapshot);
                }
              });
      record.put("status", outcome.status().name().toLowerCase());
      record.put("winner", outcome.winner());
      record.put("winners", outcome.winners());
      record.put("rounds", outcome.rounds());
      record.put("capped", outcome.capped());
      record.put("stopped", outcome.stopped());
      record.put("end", outcome.end());
      record.put("error", null);
      if (!scripted.isEmpty()) {
        record.put("script_log", scripted.stream().flatMap(p -> p.log().stream()).toList());
      }
    } catch (final RuntimeException e) {
      e.printStackTrace();
      if (game != null && !game.isGameOver()) {
        game.stopGame();
      }
      putCrash(record, e.toString());
    }
    record.put("wall_seconds", seconds(System.nanoTime() - wallStart));
    record.put("cpu_seconds", seconds(processCpuNanos() - cpuStart));
    return record;
  }

  /**
   * Player types for the kata's scripted players: each keeps the AI it would have had (the seat's
   * AI under test, or the reference AI) for the phases and turns its scripts leave out.
   */
  private static Map<String, PlayerTypes.Type> scriptedPlayers(
      final Kata kata,
      final GameData data,
      final Sides sides,
      final String seatAi,
      final Consumer<ScriptedAi> created) {
    final Map<String, Map<Integer, KataScript>> byPlayer = new TreeMap<>();
    for (final Kata.Script script : kata.scripts()) {
      final KataScript parsed = KataScript.read(script.file());
      final List<String> unknown = parsed.unknownNames(data);
      if (!unknown.isEmpty()) {
        throw new IllegalArgumentException(script.file() + ": not on this map: " + unknown);
      }
      byPlayer.computeIfAbsent(script.player(), p -> new TreeMap<>()).put(script.round(), parsed);
    }
    final Map<String, PlayerTypes.Type> types = new TreeMap<>();
    byPlayer.forEach(
        (name, scripts) -> {
          final GamePlayer player = data.getPlayerList().getPlayerId(name);
          if (player == null) {
            throw new IllegalArgumentException("scripts: no player " + name);
          }
          final String ai = sides.seat().contains(player) ? seatAi : kata.referenceAi();
          types.put(name, AiRegistry.scripted(ai, scripts, created));
        });
    return types;
  }

  /** Seat players' moves and battles, and what they place; bids and turn ends are skipped. */
  private static boolean isSeatDetailStep(final GameStep step, final Sides sides) {
    final GamePlayer player = step.getPlayerId();
    return player != null
        && sides.seat().contains(player)
        && !(step.getDelegate() instanceof BidPurchaseDelegate)
        && !(step.getDelegate() instanceof BidPlaceDelegate)
        && !(step.getDelegate() instanceof AbstractEndTurnDelegate);
  }

  private static Map<String, Object> measure(
      final GameData data, final Sides sides, final List<Metric> metrics) {
    final Map<String, Object> values = new LinkedHashMap<>();
    for (final Metric metric : metrics) {
      final double seat = metric.value(data, sides.seat());
      final double enemy = metric.value(data, sides.enemy());
      final Map<String, Object> value = new LinkedHashMap<>();
      value.put("seat", seat);
      value.put("enemy", enemy);
      value.put("gap", seat - enemy);
      values.put(metric.id(), value);
    }
    return values;
  }

  private static String describe(final Kata kata) {
    final Kata.Limits l = kata.limits();
    return String.format(
        "%-20s seats=%s reference=%s rounds=%d (thorough %d) games=%d-%d  %s",
        kata.name(),
        kata.seats(),
        kata.referenceAi(),
        l.rounds(),
        l.thoroughRounds(),
        l.minGames(),
        l.maxGames(),
        kata.description());
  }

  /** The build, commit and dirty flag the games run from: the frozen build's, else the tree's. */
  private static Map<String, Object> source(final String triplea) throws IOException {
    final Map<String, Object> source = new LinkedHashMap<>();
    final String build = System.getProperty(FROZEN_BUILD);
    if (build == null) {
      source.put("build", null);
      source.put("git_sha", gitIn(triplea, "rev-parse", "HEAD"));
      source.put("git_describe", gitIn(triplea, "describe", "--tags", "--always"));
      source.put(
          "git_dirty", !gitIn(triplea, "status", "--porcelain", "--untracked-files=no").isEmpty());
      return withBenchmarkTree(source);
    }
    // The source tree may have moved on since the freeze; the build knows what it was made from.
    final Properties frozen = new Properties();
    try (var in = Files.newBufferedReader(Path.of(build, "build.properties"))) {
      frozen.load(in);
    }
    source.put("build", Path.of(build).getFileName().toString());
    source.put("git_sha", frozen.getProperty("git_sha", "unknown"));
    source.put("git_describe", frozen.getProperty("git_describe", "unknown"));
    source.put("git_dirty", Boolean.parseBoolean(frozen.getProperty("git_dirty", "true")));
    return withBenchmarkTree(source);
  }

  /** Kata are read live from this repo, so its commit is part of what was tested. */
  private static Map<String, Object> withBenchmarkTree(final Map<String, Object> source) {
    source.put("benchmark_sha", gitIn(".", "rev-parse", "HEAD"));
    source.put(
        "benchmark_dirty", !gitIn(".", "status", "--porcelain", "--untracked-files=no").isEmpty());
    return source;
  }

  /** {@code <timestamp>-<ai>-<sha7>[-dirty]} under {@code outRoot}, also written to LATEST. */
  private static Path newRunDir(
      final Path outRoot, final String ai, final Map<String, Object> source) throws IOException {
    final String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now());
    final String slug = ai.toLowerCase().replaceAll("[^a-z0-9]+", "-");
    final String sha = (String) source.get("git_sha");
    final String commit =
        sha.substring(0, Math.min(7, sha.length()))
            + (Boolean.TRUE.equals(source.get("git_dirty")) ? "-dirty" : "");
    final Path dir = Files.createDirectories(outRoot.resolve(stamp + "-" + slug + "-" + commit));
    Files.writeString(outRoot.resolve("LATEST"), dir.toString());
    return dir;
  }

  private static void writeRunJson(final Path runDir, final Map<String, Object> run)
      throws IOException {
    Files.writeString(
        runDir.resolve("run.json"),
        new GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(run));
  }

  private static long processCpuNanos() {
    return ((com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean())
        .getProcessCpuTime();
  }

  private static double seconds(final long nanos) {
    return Math.round(nanos / 1e8) / 10.0;
  }

  private static String gitIn(final String repo, final String... args) {
    final List<String> command = new ArrayList<>(List.of("git", "-C", repo));
    command.addAll(List.of(args));
    try {
      final Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
      final String output = new String(process.getInputStream().readAllBytes()).trim();
      return process.waitFor() == 0 ? output : "unknown";
    } catch (final IOException e) {
      return "unknown";
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      return "unknown";
    }
  }

  private static boolean thorough(final Map<String, String> opts) {
    return Boolean.parseBoolean(opts.getOrDefault("thorough", "false"));
  }

  private static String require(final Map<String, String> opts, final String key) {
    final String value = opts.get(key);
    if (value == null) {
      throw new IllegalArgumentException("Missing --" + key);
    }
    return value;
  }

  private static Map<String, String> parseArgs(final String[] args) {
    final Map<String, String> opts = new TreeMap<>();
    for (int i = 0; i < args.length; i++) {
      if (!args[i].startsWith("--")) {
        throw new IllegalArgumentException("Unexpected argument: " + args[i]);
      }
      final String key = args[i].substring(2);
      if (key.equals("list")) {
        opts.put(key, "true");
      } else if (i + 1 < args.length) {
        opts.put(key, args[++i]);
      } else {
        throw new IllegalArgumentException("Missing value for --" + key);
      }
    }
    return opts;
  }

  /** Where one run's raw data goes. */
  private record Sinks(Path runDir, JsonlWriter snapshots, JsonlWriter steps, JsonlWriter games) {}

  /** The seat's players and everyone else, who count as the enemy. */
  record Sides(List<GamePlayer> seat, List<GamePlayer> enemy) {
    static Sides of(final GameData data, final String alliance) {
      final var alliances = data.getAllianceTracker().getAlliances();
      if (!alliances.contains(alliance)) {
        throw new IllegalArgumentException("No alliance '" + alliance + "'; map has " + alliances);
      }
      final var inSeat = data.getAllianceTracker().getPlayersInAlliance(alliance);
      final List<GamePlayer> players = data.getPlayerList().getPlayers();
      return new Sides(
          players.stream().filter(inSeat::contains).toList(),
          players.stream().filter(p -> !inSeat.contains(p)).toList());
    }
  }
}
