# AI benchmark

Needs `triplea-game/triplea` cloned next to this repo (`../triplea`, or another clone named with
`triplea=<dir>`); the build pulls the engine and AI from it as a Gradle composite build, and run
records name that clone's commit.

Plays a TripleA AI over a set of scenarios ("kata"), seat by seat, and
records game state at the end of every player turn. Comparing runs is the
tabulator's job: the scorecard sets any number of run folders side by side,
each kata seat a box score, each metric an inning, each run a team. To measure
a change, keep a run of the stock AI (eg `just ai=Hard run`) and compare it
with later runs; it does not need replaying.

## Run

From this folder (overrides go before the recipe):

```
just run                          # Hard Beta on every kata
just ai=Hard kata='big_*' run     # another AI, a kata glob
just kata=_pipeline run           # the pipeline check on a tiny map
just workers=8 run                # 8 games at once (default: half the cores, capped by free memory)
just minutes=60 run               # no new pair after 60 min (default 20; 0: none)
just thorough=true minutes=120 run  # play each kata's thorough_rounds instead of rounds
just baseline=<run dir> run       # stop each kata early once it clearly differs from a stored run
just freeze [name]                # freeze the runner and engine as built now (default name: the commit)
just build=<name> run             # play a frozen build while the source tree changes
just triplea=<dir> freeze [name]  # freeze from another triplea clone, eg a commit checked out apart
just list                         # kata found and their limits
just tabulate <run dir>...        # compare runs from raw data; others are diffs from the first
just compare <rev>...             # freeze and play each triplea rev in turn, then tabulate them
just compare main pr/15228        # a PR's head against main (pr/<n> fetches it from origin)
```

`compare` clones each rev of `triplea` under `build/clones/<sha7>/` and freezes it as `<sha7>`,
reusing either when it exists; the other overrides apply to every run. A rev before triplea #15225
(0408bdc8a2) lacks the replay seams the runner needs, so `freeze` refuses it; cherry-pick #15225
onto it to play it. Runs on one box take turns: `run` and `compare` hold `build/.run.lock` while
playing, so a second one prints `WAITING` and starts when the first ends.

AIs: `Hard`, `Hard Beta`, `Fast`, `Easy` (see `AiRegistry`). Kata whose folder
starts with `_` are internal and only run when the glob starts with `_` too.
Each run writes `build/<timestamp>-<ai>-<sha7>/` in this repo (`-dirty` when
the tree had uncommitted changes; a frozen build names the commit it was frozen from).

A frozen build is a copy of the runner's classpath under `build/builds/<name>/`, so a
long run can play one version of the AI while you edit and rebuild the next. It reads the kata
folder live, and its runs record the commit it was frozen from (`build`, `git_sha`, `git_dirty` in
`run.json`). `build=` runs `java` from the PATH, which must be JDK 25.

## How a kata is played

For each seat (an alliance), the AI under test plays the seat and
`reference_ai` plays everyone else. Games go in pairs, one per seat. Kata take
turns: the next pair goes to the kata furthest from its `min_games`, so each reaches it before any
plays more, up to `max_games`. No pair starts after the run's deadline (`minutes`), nor one whose
kata's games look longer than the time left (`SKIP`: its finished games' average, or how long its
oldest running game has run). A started pair is always finished, so a run ends a little past the
deadline. A kata that didn't reach `min_games` prints `SHORT`. The CPU, not the worker count,
bounds a run: each game's battle calculator already uses several cores. Whatever else the box is
running, no new game starts while free memory is under two workers' heaps or the load average is
over 2 per core (`HOLD`); it starts once a running game ends and there's room.

Each game runs in its own JVM, several at once; a game whose worker fails keeps its log under
`parts/` in the run folder. Runs are compared in the same seat only; maps
aren't balanced, so swapping sides isn't comparable.

At each player-turn end the runner records every metric for the seat, for the
enemy (every player outside the seat's alliance), and the gap between them.
The gap is the signal: +10 PUs for the seat and -10 for the enemy is +20.

## Add a kata

A folder under `kata/` holding a `kata.yaml` and its start file:

```yaml
description: Pipeline check on a tiny map
start:
  map: game.xml             # or  save: position.tsvg
seats: [Axis, Allies]       # alliances; each is benchmarked separately
reference_ai: Hard          # default Hard
limits:
  rounds: 10                # round cap per game (required)
  thorough_rounds: 30       # default rounds; the cap for thorough=true
  min_games: 2              # default 2; per seat
  max_games: 20             # default 20
  game_timeout_minutes: 30  # default 30
primary_metric: tuv         # default tuv
metrics: [tuv, income]      # default: all registered
props: {Low Luck: true}     # game options by XML name
bid: game.bid.json          # optional fixed bid, see below
scripts:                    # optional scripted turns, see below
  - {player: Germans, round: 1, file: g1.json}
```

`name` defaults to the folder name. Unknown keys, AIs, and metrics are errors.

**Bid.** `bid:` names a JSON file of extra PUs and placed units, applied to the start before round
1 without asking any AI, so every game starts from the same bid. The map's own `<player> bid`
options must be 0. Schema: `{"pus": {"Russians": 3}, "placements": [{"player": "Russians",
"territory": "Caucasus", "unitType": "infantry", "count": 2}]}`, plus free-text `map`, `source`,
`notes`.

**Human start.** `start: {map: game.xml, human: position.jsonl@3@Germans}` rebuilds a human
game's position on the map: Germany's turn start in round 3, with the human game's options, then
the kata's `props`. The file is a forum-save export (one JSON object per line: a `meta` record
with `options`, `position` records with `territories`, `step` records); it needs only the `meta`
line, the `position` line and the `step` line after it, so cut it down. This reaches 1.x-era saves
the current engine can't load; a newer save works as `start: {save: ...}` directly. Not rebuilt:
tech advances, v3 factory damage, per-unit flags. Anything the map lacks is listed in the game's
`human_missing`. `limits.rounds` is a round number, not a count: round 3 to 5 is `rounds: 5`.

**Scripted turns.** `scripts:` plays a player's turn in a round from a JSON file, whichever AI
plays that player (the AI under test on its seat, else the reference AI; Hard or Hard Beta only).
Use it to set up a situation by playing a known opening, then see what the AI does next. Every
phase is optional and the AI plays any phase left out:

```json
{
  "purchase": {"infantry": 6, "armour": 2},
  "combatMoves": [{"from": "Germany", "to": "Poland", "units": {"armour": 2}}],
  "nonCombatMoves": [{"from": "Germany", "route": ["Germany", "Western Europe"],
                      "units": {"infantry": 2}}],
  "aiFinishesMoves": false,
  "place": [{"territory": "Germany", "units": {"infantry": 6}}]
}
```

A move list replaces the AI's moves for that phase unless `aiFinishesMoves` is true; units the
script doesn't place, the AI places; an unaffordable purchase falls back to the AI's. Names are
checked against the map before any game starts. What each scripted action did, with `FAILED:` and
the engine's reason when it was refused, is the game's `script_log`.

## Early stop against a stored run

The question a run answers is "which variant moves the numbers most, and soonest", against runs
already on disk; the baseline is not replayed. With `baseline=<run dir>`, a kata stops starting
pairs once, on every seat, its primary metric's mean final gap differs from the baseline's with a
99% CI that excludes 0 (after `min_games`, at least 2, in both runs). The run prints `EARLY` and
the difference. This is the runner's only look at results while it plays; checking after every
pair inflates false stops, hence the 99% level. Measure with the tabulator, not the stop.

## Open a kata in the client

`just open <kata>` launches the TripleA client on the kata's start file. To change a
save-start position, edit it in the client and save over the kata's `.tsvg`.

## Decisive end

A full game ends early, as a win, once one alliance has led at two round ends in a row: more
than 10% more unit value and more income than everyone outside it, all its own capitals held, and
at least one enemy capital held. The round cap still applies. The thresholds are constants in
`runner/Decisive.java`, shared by every kata; a `stop_after` kata never uses the rule. The scorecard
counts a decisive game as a win for the leader; `end` in `games.jsonl` tells them apart.

## Focused runs: one turn, with detail

To study how an AI plays one position (a bug report, a specific turn), drop in a kata
for it and run only that. `limits.stop_after` ends each game when the named *player's* first
turn ends, so `rounds: 1, stop_after: Russians` plays Russia's turn and nothing after it.
A kata with `stop_after` records detail (override with `detail: true|false`):

- `steps.jsonl`: after each non-bid step of the seat's players, every land territory's owner and
  every unit-holding territory's units by type, split into the seat's side (`u`) and the rest
  (`e`). The tabulator tallies it per run: seat-owned % and mean seat units per territory per
  step, keeping the territories where the runs differ or the outcome varies (all of them in
  `scorecard.json`, the top 15 per step in the terminal).
- `logs/<kata>/<seat>/<pair>.log`: the Pro AI's own log for each game (what its log window
  shows; `=== round N <step>` lines mark where each step starts). Grep these for why.

```yaml
description: Russia round 1 on WW2v5 (forum 81063), one turn
start: {map: game.xml}          # copy the map's game XML next to this file
seats: [Allies]
limits: {rounds: 1, stop_after: Russians, min_games: 10, max_games: 10}
```

Games run one at a time in one JVM: a kata of 10 pairs takes minutes, and the default 2g heap
(`-Pheap=`) is plenty.

## Add a metric

Implement `metrics.Metric` (a value summed over a group of players) and add it
to the list in `metrics.Metrics`. The runner computes the gap. A metric that
counts what happens over a game overrides `start` and gets fresh instances per
game; the running totals below share one `GameHistory`.

Current metrics:

- Board: `tuv`, `income` (production), `pus` (on hand), `units`, `vc` (victory cities). `tuv` and
  `units` count every unit, including ones a map's display settings hide, since a headless run has
  no map resources.
- Running totals from the start, by diffing the board at each snapshot: `bought_tuv` (unit value
  put on the map), `lost_tuv` (unit value lost from it, killed or captured), `captures` (land
  territories taken), `recaptured` (captures lost again to the enemy within a round). So `tuv` is
  the start plus `bought_tuv` minus `lost_tuv`, and the `lost_tuv` gap is the trade balance. A unit
  bought and lost between two snapshots shows in neither.
- Next-turn threats, from Pro AI's enemy reach model (strongest single enemy player per territory,
  "likely wins" at its strength estimate of 60, 50 being even); lower is better: `exposure_air` and
  `exposure_land` (enemy attack power with a likely win in reach), `capitals_threatened` (own
  capitals a likely win can take), `transports_exposed` (transports in attackable sea zones with no
  own warship), `navy_exposed` (warship TUV in sea zones where the enemy likely wins).

## Raw data (schema 1)

The runner never analyses; the tabulator reads only these files.

- `run.json`: `schema`, `ai`, `build` (frozen build name, or null), `git_sha`, `git_describe`, `git_dirty` (the triplea
  commit under test), `benchmark_sha`, `benchmark_dirty` (this repo, which holds the kata), `workers`,
  `minutes` (the deadline, 0 for none), `thorough`, `baseline` (or null), `java`, `cpus`, `started_at`, `ended_at`, and `kata`: each descriptor as resolved.
- `games.jsonl`, one per game: `kata`, `seat`, `pair`, `game` (id), `ai`, `reference_ai`, `seed` (null for
  now), `status` (`ok`, `timeout`, `crash`), `winner` (alliances joined by
  `+`, or null), `winners` (players), `rounds`, `capped`, `stopped` (ended by `stop_after`),
  `end` (`won`, `decisive`, `stop`, `cap`, `timeout`; null on a crash), `error`, `wall_seconds`,
  `cpu_seconds`, for a human start `human_missing` (what the map lacked), and with scripts
  `script_log`.
- `steps.jsonl` (detail kata only), one per recorded step: `kata`, `seat`, `pair`, `game`,
  `round`, `player`, `step`, and `territories`: `{<name>: {o: owner, s: owner is in the seat, u:
  {<unit type>: n} seat's units, e: {<unit type>: n} everyone else's}}`.
- `snapshots.jsonl`, one per player-turn end plus one with `last: true` for
  the final state: `kata`, `seat`, `pair`, `game`, `round`, `player`
  (whose turn ended; null on the last record), `turn_index`, `last`, and
  `metrics`: `{<id>: {seat, enemy, gap}}`.

## Keeping results

`just keep <run> <name>` copies a clean run, minus `logs/`, to `results/baselines/<name>/`; commit
those and pass one as `baseline=`. `results/experiments/` is ignored scratch space. Push a baseline
only when its `git_sha` is on a triplea remote.

## Scorecard

The tabulator is its own module, `analysis/` (`:analysis`). It reads only the raw
data files above and has no engine dependency, so any run folder, old or copied from another
machine, can be re-tabulated after the analysis changes.

`scorecard.json`, `scorecard.html` (self-contained), and `scorecard.txt`, the box scores. The
terminal shows a summary: one row per kata seat, the primary metric's gap in run 1 and each later
run's difference from it. Cells are the mean gap at each game's final snapshot with a 95%
Student-t CI; each run after the first gets a diff row, that run minus the
first, with a Welch CI. One run is written into its own folder; several into a
new `compare-<timestamp>` folder beside the first.
Only `ok` games count; crashes and timeouts show in the record column.
A `WARNING` line (and an HTML note) appears when runs played a kata to different round caps (eg
`thorough=true` against not), since their results aren't comparable.
The signal is the metric gaps, not who won: a variant that ends every round 10 TUV further ahead,
equal elsewhere, is better whether or not the game is decided. A diff cell's `@tN` is the first
turn index where that metric's mean gap already differs from run 1's with a CI excluding 0: how
soon a change shows, where the diff itself shows how much. `score` (win 1, loss 0, anything else
½) is there for the full-game kata that judge on results.
Trajectory charts plot the mean gap per turn index with n per point, since
games that end early drop out of later turns.

Runs written when the runner still played a control AI also hold `arm`
(`control` or `experimental`) on each record; the tabulator uses only the
`experimental` games, so those folders still compare.

## Develop

`./gradlew -Ptriplea=../triplea check` runs the tests and PMD (using triplea's `.build/pmd.xml`).
The configuration cache stays off: with it on, every build in this composite ends failing on
triplea's `failedTestsService`.
