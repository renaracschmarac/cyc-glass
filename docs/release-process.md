# Release process

Releases are published as GitHub Releases on version tags. The APK should
be built by GitHub Actions, not from a developer workstation.

The current pattern is:

- Tag name: `vX.Y.Z`
- Release title: `vX.Y.Z - short feature summary`
- Attached APK asset: `cyc-glass-vX.Y.Z.apk`
- APK source: the `Android release APK` GitHub Actions workflow

The app version comes from `android-app/app/build.gradle`:

```groovy
versionCode N
versionName "X.Y.Z"
```

Before release, make sure `versionName` matches the intended tag without
the leading `v`, and bump `versionCode` when shipping a newer APK.

## Commands

From the repository root:

```bash
git status --short --branch
cd android-app
./gradlew testDebugUnitTest
cd ..
gh release create vX.Y.Z \
  --repo renaracschmarac/cyc-glass \
  --target main \
  --title "vX.Y.Z - short feature summary" \
  --notes-file /tmp/cyc-glass-vX.Y.Z-notes.md
gh workflow run "Android release APK" \
  --repo renaracschmarac/cyc-glass \
  --ref main \
  -f tag=vX.Y.Z
```

If the tag already exists, use `gh release create vX.Y.Z ...` without
`--target`, or delete and recreate the tag only when that is intentional.
If the release exists but the APK must be rebuilt, rerun the workflow with
the same tag; it uploads with `--clobber` and replaces the release asset.

## Notes

Release notes should summarize user-visible changes, permissions or setup
changes, tests run, and install commands. The install command still uses
the locally built path for manual verification, but the published release
asset comes from GitHub Actions:

```bash
cd android-app
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.cycglass.monitor/.MainActivity
```
