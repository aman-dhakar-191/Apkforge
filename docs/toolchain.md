# Toolchain evaluation (Phase 3 and later)

Checked on 2026-11-13. "Checked" means I read the published package metadata (license, versions,
dependencies, available files) from Maven. **Nothing here has been run on a phone.** Android
compatibility is marked as a guess unless stated.

| Need | Candidate | License (checked) | Fits on Android? |
|---|---|---|---|
| Archive listing | `java.util.zip` | platform | Yes. Already used and run on a device. |
| Package details | `PackageManager.getPackageArchiveInfo` | platform | Yes. Run on a device (Android 16): label, version, SDK levels, permissions, certificate all read. |
| Binary manifest to text | Own decoder (`AxmlDecoder`) | ours | Pure Kotlin, no dependencies. Checked against `aapt2 dump xmltree` output for Apkforge's own APK. |
| Resource table (`resources.arsc`) | Own reader, later; or `net.dongliu:apk-parser` 2.6.10 | BSD 2-Clause | Guess: pure Java and fine. Not tried. |
| DEX to smali, and back | `com.android.tools.smali:smali-baksmali`, `smali`, `smali-dexlib2` 3.0.10 (Google's published build of smali) | BSD 3-Clause | **Checked:** Java 8 bytecode; no calls that need a newer Android (javap scan); depends on Guava `31.1-android`. Used in Apkforge. Round trip checked on 608 classes of a real app; output accepted by `dexdump` and `d8`. Not yet run on a phone. |
| Java-like decompile | `io.github.skylot:jadx-core` 1.5.6 | Apache 2.0 | Guess: too heavy for a phone (large dependency tree, lots of memory). Deferred. |
| Signing and verifying | `com.android.tools.build:apksig` 9.5.1 | Apache 2.0 | **Checked:** Java 8 bytecode, no dependencies, none of the known Android-crash patterns in its classes (scanned with javap). Used in Apkforge. Output verified with Android's own `apksigner verify`, `zipalign -c -P 16` and `aapt2 dump badging` on a 7 MB APK. Not yet run on a phone. |
| Resource rebuilding | `aapt2` | Apache 2.0 | **No.** Google publishes it only as `linux`, `osx` and `windows` jars. The `android`, `android-arm64` and `linux-arm64` files do not exist (checked: HTTP 404). Running it on a phone would mean building and shipping our own ARM binary from AOSP source. |

## The rebuilding question

Rebuilding with `aapt2` on a phone is not realistic without compiling it ourselves for Android,
which is a large, separate project. So Apkforge should not depend on it.

Recommended approach instead, which needs no `aapt2`:

1. **Code edits**: disassemble DEX to smali, edit, assemble back (smali/dexlib2).
2. **Text and value edits**: patch the string pool of `resources.arsc` or the binary manifest
   directly, for a small set of supported edits. Replacing a same-size or smaller value is the
   easy case; anything else needs the chunk sizes and offsets rewritten, which must be tested
   against Android's own parser.
3. **Files** (images, assets): replace the zip entry as is.
4. Re-zip, align, sign with `apksig`, verify with `apksig`, then install through Android's installer.

This limits edits to a known, tested set. Anything outside it is reported as "not supported" and
not attempted. Image replacement, rebuilding, signing and installing are now built on this plan (smali is not needed for UI edits). What is still unverified is running `apksig` on a phone.
