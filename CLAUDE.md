# ai-benchmark

How to run a benchmark. `README.md` is the full reference; open it only for what this doesn't
cover.

## Compare triplea revs

```
just compare <base rev> <candidate rev>     # eg: just compare main pr/15228
```

Revs resolve in `../triplea`; `pr/<n>` fetches a PR's head. Each rev is cloned and frozen under
`build/` (reused next time) and played for `minutes` (default 20), one rev after another, then
tabulated against the first. Overrides go before the recipe: `just kata='bw42v3-*' minutes=60
compare main pr/15228`.

The terminal shows one row per kata seat: run 1's primary-metric gap, then each later run's
difference from it, `*` where the 95% CI excludes 0. The box scores are in `scorecard.txt`, the
charts in `scorecard.html`, both in the folder it prints. With n of only a few games per seat, a
wide CI means no signal, not no effect.

`just tabulate results/baselines/<name> <run dir>` compares a run with a stored baseline instead
of replaying the base. `just keep <run dir> <name>` stores one; commit it, and push it only when
its `git_sha` is on a triplea remote.

A triplea rev before #15225 (0408bdc8a2) won't freeze: cherry-pick #15225 onto it.

## Running one

- One benchmark at a time. `run` and `compare` take `build/.run.lock` (a second prints `WAITING`),
  and the pool holds new games back while the box is short of memory or CPU (`HOLD`). Never start
  benchmark JVMs by hand or several at once: three at once got OOM-killed.
- Start it with `run_in_background` and wait for the completion notice. Don't poll, sleep, or tail
  the log meanwhile; read the summary once at the end.
- Each rev takes about `minutes` plus its slowest started game: 20 min at the default, measured.
