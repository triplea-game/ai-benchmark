set shell := ["bash", "-eu", "-o", "pipefail", "-c"]

# Override on the command line, before the recipe, eg: just kata=_pipeline ai=Hard run
ai := "Hard Beta"
kata := "*"
# Parallel games, one JVM each; 0 sizes the pool from cores and free memory
workers := "0"
# A stored run folder; a kata stops early once it clearly differs from that run
baseline := ""
# A frozen build (see freeze) to play instead of the current source
build := ""
heap := "2g"
gradlew := justfile_directory() / "gradlew"
out := clean(justfile_directory() / "build")
builds := out / "builds"
baseline_arg := if baseline == "" { "" } else { canonicalize(baseline) }

# Show this help message
help:
    @just --list

# Play {{ai}} on the kata matching {{kata}}, then print the scorecard; "*" skips "_" kata
run:
    @{{ if build == "" { gradlew + " -q :runner:runBenchmark '-Pai=" + ai + "' '-Pkata=" + kata + "' -Pworkers=" + workers + " -Pheap=" + heap + " '-Pbaseline=" + baseline_arg + "'" } else { "java -Xmx" + heap + " -Dai.benchmark.build=" + builds / build + " -cp '" + builds / build / "lib/*' org.triplea.ai.benchmark.runner.BenchmarkMain --kata-dir " + justfile_directory() / "kata" + " --out " + out + " --ai '" + ai + "' --kata '" + kata + "' --workers " + workers + " --baseline '" + baseline_arg + "'" } }}
    @{{gradlew}} -q :analysis:tabulate

# Freeze the runner and engine as built now, to play later with build=<name> (default: the commit)
freeze name=`git -C ../triplea rev-parse --short HEAD`:
    @{{gradlew}} -q :runner:freeze "-PbuildName={{name}}"
    @printf 'git_sha=%s\ngit_dirty=%s\n' "$(git -C ../triplea rev-parse HEAD)" "$([ -z "$(git -C ../triplea status --porcelain --untracked-files=no)" ] && echo false || echo true)" > "{{builds / name}}/build.properties"
    @echo "FROZEN {{builds / name}}"

# List the kata found and their limits
list:
    @{{gradlew}} -q :runner:listKata

# Compare run folders' raw data; each run after the first is shown as a difference from it
tabulate +dirs:
    @{{gradlew}} -q :analysis:tabulate "-PrunDirs=$(realpath {{dirs}} | paste -sd,)"

# Open a kata's start file (map XML or save) in the TripleA client
open name:
    @{{justfile_directory() / "../triplea/gradlew"}} -q -p ../triplea :game-headed:run --args="{{absolute_path("kata" / name)}}/$(grep -oE '(map|save): *[^ ,}#]+' kata/{{name}}/kata.yaml | head -1 | sed -E 's/^[a-z]+: *//')"
