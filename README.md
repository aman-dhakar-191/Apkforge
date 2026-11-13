# Apkforge

On-device Android APK inspection, decompilation, modification, rebuilding and signing toolkit.
Local only: no root, no network permission, nothing is uploaded.

Only use it on apps you own or are authorized to analyze. It is not meant to bypass licensing,
DRM, signature checks or anti-tamper protections.

## Status: Phase 3 in progress (manifest, resources, editability check)

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
- **Installed apps**: lists the apps Android lets Apkforge see (those with a launcher icon; there is
  no `QUERY_ALL_PACKAGES`), with search and a switch for system apps. Tapping one shows its package,
  version and the APK path Android reports, and tries to copy that APK into the workspace. If
  Android refuses (it depends on the Android version), Apkforge says so in plain words and offers
  the file picker instead. It never uses root, hidden APIs or other apps' private data.
  Split APKs: only the base APK is copied for now, and the app says so.

- **Manifest tab**: the binary AndroidManifest.xml shown as readable XML (own decoder, bounds-checked,
  no dependencies). References to resources are shown by name (`@string/app_name`).
- **Resources tab**: names and default values from `resources.arsc` (own reader), with search and
  a filter by type. Only simple values are shown (strings, numbers, colours, file paths); styles,
  arrays and plurals show their item count. Other languages and screen densities are counted, not listed.
- **Split detection** reads the manifest (`requiredSplitTypes`, `split`), so the base APK of a split
  app is reported as needing its other parts instead of as "complete".

- **Editability check** (Overview): "Can this app be modified?" A checklist from reading the files:
  split app needing other parts (blocker); Google Play Integrity, SafetyNet, Firebase App Check,
  Play licensing or root-detection code present, or native libraries named like known code packers
  (warnings); native code, debuggable flag, shortened class names, and the new-signature caveat
  (notes). It reads only each DEX file's class-name table (about 0.2 s for 19 MB). It never says
  "nothing found" about code it could not read, and it lists what it cannot detect: the app's own
  signature checks, DRM, and code decrypted at run time. It reports what is there; it does not help
  get around any protection.

Not built yet (tabs show "not available yet"): smali/Java views, editing,
rebuilding, signing, installing. How rebuilding could work without `aapt2` is in
[docs/toolchain.md](docs/toolchain.md). See
[docs/plan.md](docs/plan.md).

Status of checking: 69 unit tests pass (archive parsing, path safety, workspace, debug log,
installed-app filtering and copying, manifest and resource decoding checked against `aapt2` output; all 275 resource names of Apkforge's own APK match; code scanner tested on DEX files built by d8) and a release APK builds. **The screens, `PackageManager`
reading and whether Android lets the app read other apps' APKs have not been run on a device yet.**

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
