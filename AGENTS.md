# ai-benchmark

Benchmark a triplea change: `just compare <base rev> <candidate rev>` (`pr/<n>` for a PR's head).
It prints one row per kata seat: the primary metric's gap and each later rev's difference from
the first, `*` where the 95% CI excludes 0. `just tabulate results/baselines/<baseline> <run dir>`
compares a run against a stored baseline instead.

Never start benchmark JVMs by hand or several at once: `run`/`compare` take a lock and hold new
games back when the box is short of memory or CPU. `README.md` is the full reference.
