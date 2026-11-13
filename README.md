# Apkforge

On-device Android APK inspection, decompilation, modification, rebuilding and signing toolkit.
Local only: no root, no network permission, nothing is uploaded.

Only use it on apps you own or are authorized to analyze. It is not meant to bypass licensing,
DRM, signature checks or anti-tamper protections.

## Status: Phase 1 (import and inspect)

Works now:

- Import one APK through Android's file picker. A copy goes into the app's private storage; the
  original file you picked is never changed.
- Package details Android reads from the APK: package name, label, version, min/target SDK,
  permissions, signing certificate SHA-256.
- The full list of files inside the APK (manifest, DEX, resources, assets, native libraries,
  signature files), with a filter by kind. Entries with unsafe names (`..`, absolute paths,
  duplicates) are flagged and are never unpacked.
- Safe handling of bad input: size, entry-count and unpacked-size limits; damaged or non-ZIP files
  are rejected with a reason.
- Export the untouched original APK.

Not built yet (tabs show "not available yet"): manifest decoding, resources, smali/Java views,
editing, rebuilding, signing, installing, installed-apps discovery. See
[docs/plan.md](docs/plan.md).

Status of checking: 23 unit tests pass (archive parsing, path safety, workspace, debug log) and a
release APK builds. **The screens and `PackageManager` metadata reading have not been run on a
device yet.**

## Debug log (optional)

Settings has a **Debug log** switch, off by default. When on, the app keeps a short log on the
phone: action names, counts and error types, with file names, paths and URIs removed. You can read
it in Settings and share it with **Share log**. The app has no `INTERNET` permission, so it cannot
send anything by itself; sharing is always your action.

## Building

Needs JDK 17+ and the Android SDK (platform 35, build-tools 35). Put the SDK path in
`local.properties` as `sdk.dir=...` or set `ANDROID_HOME`.

```sh
./gradlew testDebugUnitTest      # unit tests
./gradlew assembleDebug          # debug APK
```

## Releasing (same as the private app store)

Add these secrets in the repo's Settings → Secrets and variables → Actions: `KEYSTORE_BASE64`,
`KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` (generate one key with `keytool` and keep it
forever; see the store's README). Then **Actions → Release → Run workflow** and pick
patch / minor / major. Releases are published as GitHub releases with the APK attached, which is
what [private-app-store](https://github.com/aman-dhakar-191/private-app-store) reads.

To list it in the store, add an entry to that repo's `catalog.json`:

```json
{
  "name": "Apkforge",
  "package": "com.amandhakar.apkforge",
  "repo": "aman-dhakar-191/Apkforge",
  "description": "Inspect Android APKs on your phone.",
  "minSdk": 26
}
```
