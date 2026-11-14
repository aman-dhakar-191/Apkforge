# Third-party software shipped in the app

Read from each library's own Maven metadata on 2026-11-14 unless marked otherwise. Each licence allows
redistribution inside an app; the BSD licences require keeping their copyright notices with the
distribution, which the libraries' own jars carry.

| Library | Version | Licence | Used for |
|---|---|---|---|
| smali, baksmali, dexlib2, smali-util (Google's build of JesusFreke's smali) | 3.0.10 | BSD 3-Clause | reading code as smali, assembling patches |
| apksig | 9.5.1 | Apache 2.0 | signing and verifying APKs |
| ANTLR runtime | 3.5.2 | BSD (declared in its parent POM, not its own) | parsing smali |
| StringTemplate, ANTLR 2 | 3.2.1, 2.7.7 | BSD | needed by the ANTLR runtime |
| Guava (Android build), failureaccess | 31.1-android, 1.0.1 | Apache 2.0 (not declared in the POM; from the project's published licence) | used by smali |
| jsr305 | 3.0.2 | Apache 2.0 | annotations |
| AndroidX, Jetpack Compose, Kotlin | see `app/build.gradle.kts` | Apache 2.0 | the app itself |

Left out on purpose: jcommander (command-line parsing) and the ANTLR 3 grammar *tool*; the app never uses them.

Every library's class files were checked (with `javap`) for Java 8 bytecode and for calls that only exist on
newer Android versions. None of the smali/baksmali/dexlib2/apksig classes use one (the single hit,
`Buffer.clear()`, is the safe form).
