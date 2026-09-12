# CI performance investigation

The baseline is the successful `Minecraft game tests` runs with signed-publication
validation, measured using GitHub Actions run/job/step timestamps. Wall time includes
runner scheduling; runner minutes sum job durations and are not a billing estimate.

| Run | Wall time | Compile job | Sum of runner minutes |
| --- | ---: | ---: | ---: |
| [33051038948](https://github.com/sya-ri/ktAdvancements/actions/runs/33051038948) | 34m 21s | 12m 08s | 157.0 |
| [33057522336](https://github.com/sya-ri/ktAdvancements/actions/runs/33057522336) | 35m 17s | 13m 30s | 157.7 |
| [33058071727](https://github.com/sya-ri/ktAdvancements/actions/runs/33058071727) | 36m 48s | 13m 15s | 158.7 |
| [34579898613](https://github.com/sya-ri/ktAdvancements/actions/runs/34579898613) | 35m 58s | 12m 13s | 168.1 |

The latest baseline spent 68 seconds on the standalone buildSrc tests, 533 seconds on
the two plugin variants, and 115 seconds on signed-publication staging. The standalone
buildSrc invocation compiled build logic which the root invocation compiled again.
For Minecraft 1.18, Gradle started at 08:47:40 and did not reach the first server
download until 08:49:45: approximately two minutes of repeated build preparation.

Only eight matrix jobs could run together. Minecraft 26.1 started at 09:01:20,
14 minutes after compilation, and finished last. The two BuildTools jobs were the
slowest test jobs (1.20.3: 12m 02s; 26.1: 9m 38s).

## Changes

- Run all 30 versions with up to 16 concurrent jobs, placing the two BuildTools
  versions first. This overlaps slow server builds with the remaining test jobs.
- Invoke `:buildSrc:test` in the same root build as the two plugin variants, avoiding
  a separate Gradle invocation and its duplicate build-logic compilation.
- Use Gradle project parallelism with the existing two-worker limit. Reuse the
  compile job's daemon for publication staging.
- Use explicit `actions/cache` paths with one writer (compile) and restore-only matrix jobs.
  Allow PR-local writes so a cold PR run can seed its own downstream jobs. Subsequent
  runs can also restore the default branch's cache. Save before the compile job finishes,
  so dependent matrix jobs can restore the same key even on the first run.
- Cache downloaded build dependencies, generated Gradle API JARs, compiled build
  scripts, and wrapper distributions. The cache key includes the OS, architecture,
  Gradle configuration, buildSrc sources, and workflow. Task outputs and test results
  are excluded so each fresh runner compiles the current source and executes validation.

The allowlist does not retain Paperweight workspaces, artifact transforms,
server/client JARs, BuildTools work directories, assets, or worlds.
Minecraft/Paper/Spigot/Mojang dependency groups are excluded from the module cache.
The privileged `Release` workflow still disables all CI cache reads and writes.
All packet tests, four screenshot comparisons per version, JAR content checks,
release-tooling tests, and signed-publication checks remain required.

## Validation and measurement

Run actionlint, the Python release/screenshot-driver tests, and the combined Gradle
build locally. Then compare a complete PR CI run against the baseline above. Check
the restore-action logs for downstream cache hits. Record both wall time and summed job time; higher matrix
concurrency alone reduces latency, not the amount of work. A subsequent run can
measure warm compile-cache behavior separately from the first, cold run.

References: [setup-gradle caching](https://github.com/gradle/actions/blob/main/docs/setup-gradle.md),
[GitHub cache scope](https://docs.github.com/en/actions/reference/workflows-and-actions/dependency-caching),
[Gradle parallel execution](https://docs.gradle.org/current/userguide/performance.html#parallel_execution).
