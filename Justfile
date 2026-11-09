set shell := ["bash", "-eu", "-o", "pipefail", "-c"]

# Override on the command line, before the recipe, eg: just kata=_pipeline ai=Hard run
ai := "Hard Beta"
kata := "*"
# Parallel games, one JVM each; 0 sizes the pool from cores and free memory
workers := "0"
# No new pair starts after this many minutes (0: none); started pairs finish
minutes := "20"
# true plays each kata's thorough_rounds instead of rounds
thorough := "false"
# The triplea clone to build and freeze from
triplea := "../triplea"
# A stored run folder; a kata stops early once it clearly differs from that run
baseline := ""
# A frozen build (see freeze) to play instead of the current source
build := ""
heap := "2g"
gradlew := justfile_directory() / "gradlew"
gradle := gradlew + " -q '-Ptriplea=" + triplea + "'"
out := clean(justfile_directory() / "build")
builds := out / "builds"
baseline_arg := if baseline == "" { "" } else { canonicalize(baseline) }
play_command := if build == "" { gradle + " :runner:runBenchmark '-Pai=" + ai + "' '-Pkata=" + kata + "' -Pworkers=" + workers + " -Pminutes=" + minutes + " -Pthorough=" + thorough + " -Pheap=" + heap + " '-Pbaseline=" + baseline_arg + "'" } else { "java -Xmx" + heap + " -Dai.benchmark.build=" + builds / build + " -cp '" + builds / build / "lib/*' org.triplea.ai.benchmark.runner.BenchmarkMain --kata-dir " + justfile_directory() / "kata" + " --out " + out + " --ai '" + ai + "' --kata '" + kata + "' --workers " + workers + " --minutes " + minutes + " --thorough " + thorough + " --baseline '" + baseline_arg + "'" }
# One run at a time on this box: a second waits for the first
lock := out / ".run.lock"
just := just_executable() + " -f " + justfile()
# The overrides a recipe passes on when it calls another
overrides := "ai='" + ai + "' kata='" + kata + "' workers=" + workers + " minutes=" + minutes + " thorough=" + thorough + " heap=" + heap + " baseline='" + baseline + "'"
# Where the runner's replay seams live; triplea before #15225 lacks them
seams := "game-app/game-core/src/main/java/games/strategy/triplea/ai/pro/logging/ProLogUi.java"

# Show this help message
help:
    @just --list

# Play {{ai}} on the kata matching {{kata}}, then print the scorecard; "*" skips "_" kata
run:
    @dir="$({{just}} {{overrides}} triplea='{{triplea}}' build='{{build}}' play | tee >(cat >&2) | sed -n 's/^RUN //p')"; {{gradle}} :analysis:tabulate "-PrunDirs=$dir"

# Play without tabulating, holding the box's run lock; prints RUN <dir>
[private]
play:
    #!/usr/bin/env bash
    set -euo pipefail
    mkdir -p '{{out}}'
    exec 9>'{{lock}}'
    flock -n 9 || { echo "WAITING for another benchmark run to finish ({{lock}})" >&2; flock 9; }
    {{play_command}}

# Freeze each triplea revision (a rev in {{triplea}}, or pr/<n> for a PR's head), play them in turn, tabulate against the first
compare +revs:
    #!/usr/bin/env bash
    set -euo pipefail
    dirs=()
    for rev in {{revs}}; do
      if [[ $rev == pr/* ]]; then
        git -C '{{triplea}}' fetch -q origin "pull/${rev#pr/}/head"
        rev=FETCH_HEAD
      fi
      sha=$(git -C '{{triplea}}' rev-parse --short=7 "$rev^{commit}")
      clone='{{out}}'/clones/$sha
      if [ ! -d "$clone" ]; then
        git clone -q --shared --no-checkout '{{triplea}}' "$clone"
        git -C "$clone" checkout -q --detach "$sha"
      fi
      [ -d '{{builds}}'/"$sha" ] || {{just}} triplea="$clone" freeze "$sha"
      dirs+=("$({{just}} {{overrides}} build="$sha" play | tee >(cat >&2) | sed -n 's/^RUN //p')")
    done
    {{just}} triplea='{{triplea}}' tabulate "${dirs[@]}"

# Freeze the runner and engine as built now, to play later with build=<name> (default: the commit)
freeze name=`git -C {{triplea}} rev-parse --short HEAD`:
    @grep -qs setLogSink '{{triplea / seams}}' || { echo "{{triplea}} predates triplea #15225 (0408bdc8a2), whose replay seams the runner needs: cherry-pick it there" >&2; exit 1; }
    @{{gradle}} :runner:freeze "-PbuildName={{name}}"
    @printf 'git_sha=%s\ngit_describe=%s\ngit_dirty=%s\n' "$(git -C {{triplea}} rev-parse HEAD)" "$(git -C {{triplea}} describe --tags --always)" "$([ -z "$(git -C {{triplea}} status --porcelain --untracked-files=no)" ] && echo false || echo true)" > "{{builds / name}}/build.properties"
    @echo "FROZEN {{builds / name}}"

# List the kata found and their limits
list:
    @{{gradle}} :runner:listKata

# Compare run folders' raw data; each run after the first is shown as a difference from it
tabulate +dirs:
    @{{gradle}} :analysis:tabulate "-PrunDirs=$(realpath {{dirs}} | paste -sd,)"

# Copy a run from build/ into results/baselines/<name>, without logs; refuses a dirty run
keep run name:
    @if grep -Eq '"(git|benchmark)_dirty": true' "{{run}}/run.json"; then echo "dirty run, not kept" >&2; exit 1; fi
    @mkdir -p "results/baselines/{{name}}"
    @for f in run.json games.jsonl snapshots.jsonl scorecard.json steps.jsonl; do [ ! -f "{{run}}/$f" ] || cp "{{run}}/$f" "results/baselines/{{name}}/"; done

# Open a kata's start file (map XML or save) in the TripleA client
open name:
    @{{triplea / "gradlew"}} -q -p {{triplea}} :game-headed:run --args="{{absolute_path("kata" / name)}}/$(grep -oE '(map|save): *[^ ,}#]+' kata/{{name}}/kata.yaml | head -1 | sed -E 's/^[a-z]+: *//')"
