# Development

## Project Structure

| Module | Responsibility |
| --- | --- |
| `api` | Advancement definitions, runtime contracts, and storage interfaces. |
| `runtime/v*` | Server-version implementations; the aggregate runtimes select the appropriate artifacts. |
| `store/sqlite`, `store/mysql` | Persistent progress storage. |
| `example` | A consuming plugin showing the public API. |
| `game-test` | Real-server and client screenshot verification. |

See [runtime options](runtimes.md) for supported versions and mappings, and [releasing](../RELEASING.md) for publication checks.

## Game tests and screenshots

Use JDK 25 for Gradle and install the Java 17/21/25 toolchains needed by the selected game versions.
Ordinary `build` and `check` do not launch Minecraft. The opt-in game tasks build this checkout and test the runtimes under `runtime/`.
These tasks download Minecraft software and accept the EULA for disposable test servers; run them only if you own Minecraft and accept the [Minecraft EULA](https://www.minecraft.net/eula).

```sh
./gradlew :game-test:gameTestAll
# One server version:
./gradlew :game-test:gameTest26_2
```

Screenshot tests additionally launch a client and compare four advancement-progress stages against [committed baselines](../game-test/src/test/screenshots).
Linux x86_64 capture requires Python 3, xdotool, Xvfb, xauth, and Minecraft's OpenGL/audio dependencies; the [CI workflow](../.github/workflows/game-test.yml) contains the setup.

```sh
xvfb-run -a -s '-screen 0 1280x720x24' ./gradlew :game-test:screenshotTest26_2
xvfb-run -a -s '-screen 0 1280x720x24' ./gradlew :game-test:screenshotTestAll --continue
```

Windows x86_64 uses manual F2 capture:

```powershell
.\gradlew.bat :game-test:screenshotTest26_2
```

At the capture prompt, press **L**, hover the stone `Progress` icon, and press **F2** for each stage.
Keep the window size, GUI scale, screen, and cursor position unchanged.
Join the displayed loopback address if prompted; Linux can use manual capture with `-PgameTestScreenshotDriver=manual`.

### Updating baselines

Missing or differing baselines fail the test. CI never updates them.
To accept an intentional visual change, regenerate the selected version, review the PNG diff, and rerun without the update flag:

```sh
xvfb-run -a -s '-screen 0 1280x720x24' ./gradlew :game-test:screenshotTest26_2 -PupdateGameTestScreenshots=true
git diff --stat -- game-test/src/test/screenshots
xvfb-run -a -s '-screen 0 1280x720x24' ./gradlew :game-test:screenshotTest26_2
```

Use `screenshotTestAll` with the same flag to regenerate all versions.
Image-content checks still apply when updating a baseline.

### Results and diagnostics

Results live under `game-test/build/`: `visual/<version>/` holds captures, comparison reports, and client logs; `servers/run-<version>/` holds packet-test results and server logs.
CI uploads comparisons and failure diagnostics for each version. Tests select the required mapped test-plugin JAR automatically.
Exact-version Spigot overrides are available through `-PgameTestSpigot1_20_3Jar=<path>` and `-PgameTestSpigot26_1Jar=<path>` when supplying local servers.

The validator and capture driver also have GUI-free tests:

```sh
./gradlew -p buildSrc test
python3 -B -m unittest discover -s game-test/scripts -p 'test_*.py'
```

Clients use isolated directories and servers bind to loopback.
Do not commit or upload downloaded Minecraft software, assets, worlds, or account files.
