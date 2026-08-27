# Advancement screenshot baselines

Each supported Minecraft version has four **real vanilla-client F2 screenshots**:
`<version>/{zero,partial,complete,revoked}.png` (0/10, 3/10, 10/10, 9/10).
The full screenshots are committed for review. Tests compare only the stable advancement
window and tooltip; the world, chat, and other changing content are not baselines.

Ordinary `screenshotTest<version>` tasks fail for missing or different baselines and never
rewrite them. To intentionally regenerate a version after reviewing a UI change:

```sh
xvfb-run -a -s '-screen 0 1280x720x24' ./gradlew :game-test:screenshotTest26_2 -PupdateGameTestScreenshots=true
```

The update first requires all four real captures to pass the advancement-content checks.
Review and commit the changed PNGs, then run the same task without the update flag.
Use `screenshotTestAll` to apply the same procedure to the entire version matrix.

Failed comparisons write expected/actual/diff images and a report under
`game-test/build/visual/<version>/comparison/`. CI uploads these for inspection; it never
approves a new baseline automatically. A missing baseline remains a failure even though
the actual four screenshots are preserved for review.

Only screenshots belong here. Do not commit Minecraft software, assets, worlds, or account data.
