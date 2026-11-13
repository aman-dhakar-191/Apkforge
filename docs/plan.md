# Plan

1. **Import and inspect** (done, untested on a device): file picker import, private workspace,
   archive listing, package metadata, export original, optional local debug log.
2. **Installed apps** (done, untested on a device): list visible apps; try to copy an APK only if Android allows it, else fall
   back to the file picker. No root, no hidden APIs, no `QUERY_ALL_PACKAGES`.
3. **Decode and inspect** (manifest and resource names done; smali next): readable manifest, resources, smali. Java-like view only if a library
   proves reliable on-device.
4. **Controlled edits**: a few explicit edits (text resources, an image) with a diff before rebuild.
5. **Rebuild, sign, verify**: without `aapt2` (no Android build exists; see
   [toolchain.md](toolchain.md)): smali for code, direct patching for a few resource edits, `apksig`
   for signing.
6. **Install** through Android's package installer only. Never silent, never an automatic uninstall.

## Open questions that need a device

- Can `ApplicationInfo.sourceDir` of other apps be read on the Android versions you use?
- Does `getPackageArchiveInfo` read labels and signing certificates for your test APKs?
- Behaviour with large APKs (100 MB+) and split APK sets.
