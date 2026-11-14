# Apkforge

On-device Android APK inspection, decompilation, modification, rebuilding and signing toolkit.
Local only: no root, no network permission, nothing is uploaded.

Only use it on apps you own or are authorized to analyze. It is not meant to bypass licensing,
DRM, signature checks or anti-tamper protections.

## Status: code view and patcher added (Phase 3/4)

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
  Split apps: the base and all the other parts Android lists are copied (into `input/splits/`),
  best effort: a part Android refuses is reported and the rest are kept. The Overview shows the
  whole set (sizes, native libraries and resources per part). The parts are copies from this phone,
  so they match its processor, screen density and languages. Editing a whole set is not built yet.

- **Manifest tab**: the binary AndroidManifest.xml shown as readable XML (own decoder, bounds-checked,
  no dependencies). References to resources are shown by name (`@string/app_name`).
- **Resources tab**: names and default values from `resources.arsc` (own reader), with search and
  a filter by type. Only simple values are shown (strings, numbers, colours, file paths); styles,
  arrays and plurals show their item count. Other languages and screen densities are counted, not listed.
- **Split detection** reads the manifest (`requiredSplitTypes`, `split`), so the base APK of a split
  app is reported as needing its other parts instead of as "complete".

- **Editability check** (Overview): "Can this app be modified?" A checklist from reading the files:
  system app, or split app (blockers: a system app can't be replaced by a modified copy; whole-set editing is not built); Google Play Integrity, SafetyNet, Firebase App Check,
  Play licensing or root-detection code present, or native libraries named like known code packers
  (warnings); native code, debuggable flag, shortened class names, and the new-signature caveat
  (notes). It reads only each DEX file's class-name table (about 0.2 s for 19 MB). It never says
  "nothing found" about code it could not read, and it lists what it cannot detect: the app's own
  signature checks, DRM, and code decrypted at run time. It reports what is there; it does not help
  get around any protection.

- **Install as a separate copy (default).** The copy gets a new package name (`<original>.forge`, you
  can change the ending) and a new name under the icon, so it installs *next to* the original: no
  uninstalling, no lost data, and it works for system apps too (as an ordinary app). Renamed:
  `package`, content-provider authorities and every permission the app itself defines (they must be unique on the phone, even when their names don't start with the package name; references follow, permissions owned by other apps are left alone),
  the launcher name, and the package name inside `resources.arsc`. Class names are not renamed (they
  point at code inside the app). Not possible for apps that use `sharedUserId`, or for split apps (not
  built yet). Things tied to the original (Google sign-in, push notifications, licence checks) usually
  stop working in the copy. Checked on Apkforge's own 7 MB APK with `aapt2`, `apksigner` and
  `zipalign`; not yet run on a phone.
- **Previews and a file viewer.** The Resources and Build tabs show a small picture next to every
  drawable and mipmap (plain image files are decoded directly; vectors, shapes and adaptive icons are
  drawn by Android itself from the APK's resources, using public APIs; resources that need the app's
  theme show a dash), and a colour square next to colour values. Tap a resource, or a file in the Files
  tab, to open it: Apkforge looks at the file's bytes and says what it is. **XML: yes** for Android's
  compiled (binary) XML, which is shown decoded with references by name (`@drawable/...`),
  sizes as `8.0dp` / `14.0sp` and `match_parent` / `wrap_content`; **XML: no** for pictures, plain
  text and other data (hex view). Files up to 16 MB.
- **App icon finder and drawings.** The Build tab starts with an **App icon** section found from the
  manifest: for an adaptive icon it lists the foreground and background pictures (and the whole icon as
  one flat picture). The list below shows *every* drawable and mipmap, not only picture files. A
  vector or other XML drawing can be replaced by your picture: Android loads a drawable as a drawing when its
  file name ends in `.xml`, so the file is renamed `.png` and the file path in `resources.arsc` is patched in
  place (`xml` and `png` are both three letters, so nothing in the table moves). The picture is made 4 pixels
  per dp of the drawing's size (432 px when it has none). Colours are not replaceable yet.
  Checked on Apkforge's own APK: `aapt2` reads the icon foreground as `res/Qr.png`, the adaptive icon is
  untouched, and `apksigner` and `zipalign` pass.
- **Code tab: read an app's code as smali, and patch it.** Lists every class of every code file (13,623
  classes of a real 7 MB app in 0.4 s), opens one class at a time as smali (Google's baksmali), and lets you
  edit it. **Check** assembles your text without saving; **Save patch** stores it only if it assembles. A
  patch goes into the copy as a small new first code file (`classes.dex`), and the existing code files move
  up by one (`classes.dex` → `classes2.dex`...). Android loads them in order and the first file that defines
  a class wins, so the original code stays exactly as it was. Limits: callers that had the old method copied
  into them ("inlined") keep the old behaviour; smali that assembles but breaks Android's verifier fails
  when the class loads; classes over 300 thousand characters cannot be edited on the phone. This is an
  editor for understanding and fixing code you own or may analyze. It has no ready-made patches, and is not
  meant for removing licence checks, paywalls or other protections.
  Checked: 608 classes of a real app round-trip through smali with identical code (2 differ only by
  redundant `= null` / `= false` static initialisers, which are the defaults anyway); an edited class of a
  real 7 MB app, rebuilt and signed, passes `apksigner`, `zipalign`, `dexdump` and `d8`.
- **Edit resource values.** Tap a resource without a file in the Resources tab (strings, colours, numbers,
  on/off flags, sizes, fractions, references, array and plural items) to change its value, per language or
  configuration. Changes are planned, shown in the Build tab, and applied to the copy by rewriting
  `resources.arsc` (new text is appended to the string pool; numbers are written in place). Sizes are
  encoded exactly like `aapt2` (checked bit for bit on 26 values). Checked with `aapt2 dump resources` on a
  test file and on Apkforge's own 7 MB APK: only the edited lines differ; `apksigner` and `zipalign` pass.
  **Not built: adding brand-new resources.** Existing code cannot refer to a new ID unless its smali is also
  edited, and adding an entry means rewriting the key pool and every configuration's entry table.
- **Edit manifest values.** In the Manifest tab, **Edit values** lists every attribute of
  `AndroidManifest.xml` (searchable). Text, whole numbers and on/off flags can be changed (for example
  `versionName`, `versionCode`, `allowBackup`); references and the package name cannot (the package name is
  changed with "Install as a separate app"). New text is appended to the manifest's string table and nothing else
  moves. Checked with `aapt2 dump xmltree` on Apkforge's own APK: only the three edited attributes differ.
  There are no ready-made patches; a wrong value can stop the app from installing or opening.
- **Build tab: replace images.** Pick an image resource (every screen-density file it uses is found
  from `resources.arsc`), pick a picture, and Apkforge converts it to the same format and pixel size
  (centre-cropped). A summary of the planned changes is shown, then **Build, sign and check**
  rebuilds a copy (original untouched), signs it with Apkforge's own key, and verifies the
  signature. The planned changes, the Build button and the result stay pinned at the bottom of the tab. Then **Export APK** or **Install…** (Android shows its own confirmation; it never
  installs, replaces or uninstalls silently). If Android refuses, the screen shows Android's own
  message with a plain explanation of its error code, and the code (never app names) goes in the debug log. Not available for system apps or split apps.
- **Signing key** (Settings): created on first build, kept in the app's private storage, never
  logged. A damaged key is reported, never silently replaced. The modified copy has a different
  signature than the original, so Android will not install it over the original; you would have to
  uninstall the original first (deleting its app data), which Apkforge never does for you.

Not built yet: editing layouts, text and colours (planned, in that order), a Java-like view (jadx is too heavy for a phone). Third-party software and licences: [docs/third-party.md](docs/third-party.md). How rebuilding could work without `aapt2` is in
[docs/toolchain.md](docs/toolchain.md). See
[docs/plan.md](docs/plan.md).

Status of checking: 178 unit tests pass (archive parsing, path safety, workspace, debug log,
installed-app filtering and copying, split-set copying, manifest and resource decoding checked against `aapt2` output; all 275 resource names of Apkforge's own APK match; code scanner tested on DEX files built by d8; rebuilt and signed APKs accepted by Android's apksigner, zipalign -P 16 and aapt2) and a release APK builds. **The screens, `PackageManager`
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
