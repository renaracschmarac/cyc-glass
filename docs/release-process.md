# Release process

Releases are published as GitHub Releases on version tags. The current
pattern is:

- Tag name: `vX.Y.Z`
- Release title: `vX.Y.Z - short feature summary`
- Attached APK asset: `cyc-glass-vX.Y.Z.apk`
- APK source: `android-app/app/build/outputs/apk/debug/app-debug.apk`

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
./gradlew assembleDebug
cd ..
cp android-app/app/build/outputs/apk/debug/app-debug.apk cyc-glass-vX.Y.Z.apk
gh release create vX.Y.Z cyc-glass-vX.Y.Z.apk \
  --repo renaracschmarac/cyc-glass \
  --target main \
  --title "vX.Y.Z - short feature summary" \
  --notes-file /tmp/cyc-glass-vX.Y.Z-notes.md
rm cyc-glass-vX.Y.Z.apk
```

If the tag already exists, use `gh release create vX.Y.Z ...` without
`--target`, or delete and recreate the tag only when that is intentional.

## Notes

Release notes should summarize user-visible changes, permissions or setup
changes, tests run, and install commands:

```bash
cd android-app
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.cycglass.monitor/.MainActivity
```
