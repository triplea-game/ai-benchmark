package org.triplea.ai.benchmark.runner;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.management.ManagementFactory;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import javax.annotation.Nullable;

/**
 * Plays every game of a run in parallel, one game per worker JVM; games can't share a JVM because
 * ProAI's battle calculator is static. Each worker writes to its own part folder and the pool, the
 * only writer of the run's raw data files, appends a part once its worker exits.
 *
 * <p>Kata take turns: the next pair goes to the kata furthest from its {@code min_games}, so each
 * reaches it before any plays more, up to {@code max_games}. The run's deadline stops new pairs,
 * and so, near it, does a kata's average game taking longer than the time left; a started pair is
 * finished, so every seat gets the same number of games. With a baseline run, {@link EarlyStop} can
 * also stop a kata's new pairs once it clearly differs from it.
 */
final class WorkerPool {
  /** Left free for the OS and whatever else the box runs when sizing the pool. */
  private static final long MEMORY_FLOOR_MB = 1024;

  /** A JVM's footprint beyond its heap, a rough guess. */
  private static final long JVM_OVERHEAD_MB = 512;

  private final Path kataDir;
  private final Path runDir;
  private final String ai;
  private final int workers;
  private final List<KataQueue> queues;
  private final Writers writers;
  @Nullable private final EarlyStop earlyStop;
  @Nullable private final Duration deadline;
  private final boolean thorough;
  private final Map<Process, Running> running = new ConcurrentHashMap<>();
  private long deadlineAt;
  private boolean deadlineReported;

  WorkerPool(
      final Path kataDir,
      final Path runDir,
      final String ai,
      final int workers,
      final List<Kata> katas,
      final Writers writers,
      @Nullable final EarlyStop earlyStop,
      @Nullable final Duration deadline,
      final boolean thorough) {
    this.kataDir = kataDir;
    this.runDir = runDir;
    this.ai = ai;
    this.workers = workers;
    this.queues = katas.stream().map(KataQueue::new).toList();
    this.writers = writers;
    this.earlyStop = earlyStop;
    this.deadline = deadline;
    this.thorough = thorough;
  }

  /** The raw data files every worker's part is appended to. */
  record Writers(JsonlWriter snapshots, JsonlWriter steps, JsonlWriter games) {}

  /**
   * Half the cores, since each game's battle calculator already spreads over all of them, capped by
   * how many workers' heaps fit in the memory available now.
   */
  static int defaultWorkers() {
    final int byCpu = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
    final long perWorkerMb = heapMb() + JVM_OVERHEAD_MB;
    return memAvailableMb()
        .map(mb -> (int) Math.max(1, Math.min(byCpu, (mb - MEMORY_FLOOR_MB) / perWorkerMb)))
        .orElse(byCpu);
  }

  void run() throws InterruptedException {
    final Thread killWorkers = new Thread(() -> running.keySet().forEach(Process::destroyForcibly));
    Runtime.getRuntime().addShutdownHook(killWorkers);
    deadlineAt = deadline == null ? Long.MAX_VALUE : System.nanoTime() + deadline.toNanos();
    while (true) {
      while (running.size() < workers) {
        final Optional<Job> job = nextJob();
        if (job.isEmpty()) {
          break;
        }
        launch(job.get());
      }
      if (running.isEmpty()) {
        break;
      }
      finish(awaitAnyExit());
    }
    Runtime.getRuntime().removeShutdownHook(killWorkers);
    for (final KataQueue queue : queues) {
      if (queue.nextPair < queue.kata.limits().minGames()) {
        System.out.printf(
            "SHORT %s played %d pair(s), min_games is %d%n",
            queue.kata.name(), queue.nextPair, queue.kata.limits().minGames());
      }
    }
    try {
      Files.deleteIfExists(runDir.resolve("parts"));
    } catch (final DirectoryNotEmptyException e) {
      // Failed games keep their parts for their worker logs.
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * A started pair's next seat, else a new pair for the kata furthest from its {@code min_games}
   * (then from its {@code max_games}), skipping a kata whose games look longer than the time left.
   */
  private Optional<Job> nextJob() {
    final Optional<KataQueue> started =
        queues.stream().filter(q -> !q.pending.isEmpty()).findFirst();
    if (started.isPresent()) {
      return Optional.of(started.get().pending.poll());
    }
    if (queues.stream().noneMatch(KataQueue::canStartPair)) {
      return Optional.empty();
    }
    final long left = deadlineAt - System.nanoTime();
    if (left < 0) {
      if (!deadlineReported) {
        deadlineReported = true;
        System.out.printf(
            "DEADLINE %d min reached: no new pairs, %d game(s) still running%n",
            deadline.toMinutes(), running.size());
      }
      return Optional.empty();
    }
    return queues.stream()
        .filter(KataQueue::canStartPair)
        .filter(q -> fits(q, left))
        .min(Comparator.comparingDouble(KataQueue::progress))
        .map(q -> q.startPair().pending.poll());
  }

  private void launch(final Job job) {
    final Path partDir = runDir.resolve("parts").resolve(job.partName());
    final List<String> command = new ArrayList<>();
    command.add(ProcessHandle.current().info().command().orElse("java"));
    command.addAll(ManagementFactory.getRuntimeMXBean().getInputArguments());
    command.addAll(
        List.of(
            "-cp",
            System.getProperty("java.class.path"),
            BenchmarkMain.class.getName(),
            "--kata-dir",
            kataDir.toString(),
            "--play",
            job.kata().name(),
            "--seat",
            job.seat(),
            "--pair",
            String.valueOf(job.pair()),
            "--ai",
            ai,
            "--run-dir",
            runDir.toString(),
            "--part-dir",
            partDir.toString(),
            "--thorough",
            String.valueOf(thorough)));
    try {
      Files.createDirectories(partDir);
      final Process process =
          new ProcessBuilder(command)
              .redirectErrorStream(true)
              .redirectOutput(partDir.resolve("worker.log").toFile())
              .start();
      running.put(process, new Running(job, partDir, System.nanoTime()));
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private Process awaitAnyExit() throws InterruptedException {
    while (true) {
      for (final Process process : running.keySet()) {
        if (!process.isAlive()) {
          return process;
        }
      }
      Thread.sleep(250);
    }
  }

  /** Appends the worker's part to the run's files, or a crash record if it wrote no game. */
  private void finish(final Process process) {
    final Running done = running.remove(process);
    final Job job = done.job();
    final Path log = done.partDir().resolve("worker.log");
    final List<String> gameLines = lines(done.partDir().resolve("games.jsonl"));
    final List<String> snapshotLines = lines(done.partDir().resolve("snapshots.jsonl"));
    snapshotLines.forEach(writers.snapshots()::writeLine);
    lines(done.partDir().resolve("steps.jsonl")).forEach(writers.steps()::writeLine);
    final Map<String, Object> record;
    if (gameLines.isEmpty()) {
      record = BenchmarkMain.gameRecord(job.kata(), job.seat(), ai, job.pair());
      BenchmarkMain.putCrash(
          record, "worker exited " + process.exitValue() + " with no result; see " + log);
      writers.games().write(record);
    } else {
      gameLines.forEach(writers.games()::writeLine);
      record = JsonlWriter.parse(gameLines.get(0));
    }
    BenchmarkMain.printGame(record);
    if ("ok".equals(record.get("status")) && record.get("wall_seconds") instanceof Number wall) {
      queue(job.kata()).observeGame(wall.doubleValue());
    }
    if ("ok".equals(record.get("status")) && earlyStop != null) {
      checkEarlyStop(job, snapshotLines);
    }
    if ("ok".equals(record.get("status"))) {
      deleteTree(done.partDir());
    } else {
      System.out.println("  worker log: " + log);
    }
  }

  private void checkEarlyStop(final Job job, final List<String> snapshotLines) {
    snapshotLines.stream()
        .map(JsonlWriter::parse)
        .filter(s -> Boolean.TRUE.equals(s.get("last")))
        .forEach(s -> earlyStop.observe(job.kata().name(), job.seat(), s));
    final KataQueue queue = queue(job.kata());
    if (!queue.stopped) {
      earlyStop
          .decided(job.kata())
          .ifPresent(
              reason -> {
                queue.stopped = true;
                System.out.printf(
                    "EARLY %s stopped after %d pair(s): differs from the baseline, %s%n",
                    job.kata().name(), queue.nextPair, reason);
              });
    }
  }

  /**
   * Whether a new game of the kata is likely to end within {@code nanos}: its finished games'
   * average, or failing that how long its oldest running game has run so far, must fit.
   */
  private boolean fits(final KataQueue queue, final long nanos) {
    final long now = System.nanoTime();
    final double runningSeconds =
        running.values().stream()
            .filter(r -> r.job().kata() == queue.kata)
            .mapToDouble(r -> (now - r.startedAt()) / 1e9)
            .max()
            .orElse(0);
    final double expected = Math.max(queue.meanWallSeconds(), runningSeconds);
    if (expected * 1e9 <= nanos) {
      return true;
    }
    if (!queue.tooLongReported) {
      queue.tooLongReported = true;
      System.out.printf(
          "SKIP %s: its games take %.0fs or more, longer than the %.0fs left%n",
          queue.kata.name(), expected, nanos / 1e9);
    }
    return false;
  }

  private KataQueue queue(final Kata kata) {
    return queues.stream().filter(q -> q.kata == kata).findFirst().orElseThrow();
  }

  private static List<String> lines(final Path file) {
    try {
      return Files.exists(file) ? Files.readAllLines(file) : List.of();
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static void deleteTree(final Path dir) {
    try (Stream<Path> paths = Files.walk(dir)) {
      for (final Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.delete(path);
      }
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static long heapMb() {
    return Runtime.getRuntime().maxMemory() / (1024 * 1024);
  }

  /** {@code MemAvailable} from {@code /proc/meminfo}; empty off Linux. */
  private static Optional<Long> memAvailableMb() {
    try (Stream<String> lines = Files.lines(Path.of("/proc/meminfo"))) {
      return lines
          .filter(line -> line.startsWith("MemAvailable:"))
          .findFirst()
          .map(line -> Long.parseLong(line.replaceAll("\\D", "")) / 1024);
    } catch (final IOException | UncheckedIOException e) {
      return Optional.empty();
    }
  }

  /** One game to play. */
  record Job(Kata kata, String seat, int pair) {
    String partName() {
      return (kata.name() + "-" + seat + "-" + pair).replaceAll("[^\\w.-]", "_");
    }
  }

  private record Running(Job job, Path partDir, long startedAt) {}

  /** A kata's games in pair order. */
  private static final class KataQueue {
    private final Kata kata;
    private final Deque<Job> pending = new ArrayDeque<>();
    private int nextPair;
    private boolean stopped;
    private boolean tooLongReported;
    private int gamesSeen;
    private double wallSecondsSeen;

    KataQueue(final Kata kata) {
      this.kata = kata;
    }

    boolean canStartPair() {
      return !stopped && nextPair < kata.limits().maxGames();
    }

    /** Below 1 while short of min_games, then 1 plus the share of max_games played. */
    double progress() {
      final Kata.Limits limits = kata.limits();
      return nextPair < limits.minGames()
          ? (double) nextPair / limits.minGames()
          : 1 + (double) nextPair / limits.maxGames();
    }

    void observeGame(final double wallSeconds) {
      gamesSeen++;
      wallSecondsSeen += wallSeconds;
    }

    /** Mean wall time of its finished games; 0 before any. */
    double meanWallSeconds() {
      return gamesSeen == 0 ? 0 : wallSecondsSeen / gamesSeen;
    }

    KataQueue startPair() {
      for (final String seat : kata.seats()) {
        pending.add(new Job(kata, seat, nextPair));
      }
      nextPair++;
      return this;
    }
  }
}
