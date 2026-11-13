package com.amandhakar.apkforge

import com.amandhakar.apkforge.build.*
import com.amandhakar.apkforge.core.ResourceResult
import com.amandhakar.apkforge.inspect.ArscReader
import com.amandhakar.apkforge.inspect.AxmlDecoder
import com.amandhakar.apkforge.inspect.AxmlException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PackageCloneTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun res(n: String) = javaClass.getResourceAsStream("/$n")!!.readBytes()
    private val old = "com.example.clonefx"
    private val spec = CloneSpec(old, "forge", "Clone Fixture (copy)")
    private fun ok(r: CloneResult) = r as CloneResult.Ok
    private val result by lazy { ok(PackageClone.apply(res("fx_clone_manifest.bin"), res("fx_clone.arsc"), spec)) }
    private val xml by lazy { AxmlDecoder().decode(result.replacements.getValue("AndroidManifest.xml")) }

    @Test fun packageAndAuthoritiesAndPermissionsAreRenamed() {
        assertTrue(xml, xml.contains("package=\"$old.forge\""))
        assertTrue(xml.contains("android:authorities=\"$old.forge.fileprovider\""))
        // one authority already unrelated to the package: renamed with the suffix so two copies can coexist
        assertTrue(xml, xml.contains("android:authorities=\"com.other.fixed.authority.forge;$old.forge.second\""))
        assertTrue(xml.contains("android:name=\"$old.forge.permission.PRIV\""))
        assertTrue(xml.contains("android:name=\"$old.forge.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION\""))
        assertTrue(xml.contains("android:permission=\"$old.forge.permission.PRIV\"")) // the service's
        assertTrue(xml.contains("android:process=\"$old.forge.proc\""))
        assertTrue(xml.contains("android:taskAffinity=\"$old.forge.aff\""))
    }

    @Test fun thingsThatMustNotChangeAreUntouched() {
        assertTrue(xml.contains("android:name=\"android.permission.CAMERA\""))
        assertTrue(xml.contains("android:process=\":remote\""))
        // class names still point at the code inside the app
        for (c in listOf("MainActivity", "NoLabelActivity", "Two", "Svc", "Rcv", "Rcv2")) assertTrue(c, xml.contains("android:name=\"$old.$c\""))
        // and the old package name appears nowhere else as a name
        val leftovers = Regex("android:(name|authorities|permission|process|taskAffinity)=\"([^\"]*)\"").findAll(xml)
            .map { it.groupValues[2] }.filter { it.startsWith("$old.") && !it.startsWith("$old.forge") }.toSet()
        assertEquals(setOf("MainActivity", "NoLabelActivity", "Two", "Svc", "Rcv", "Rcv2").map { "$old.$it" }.toSet(), leftovers)
    }

    @Test fun theHomeScreenNameIsChangedOnlyWhereItShows() {
        assertTrue(xml, xml.contains("android:label=\"Clone Fixture (copy)\""))
        assertEquals(2, Regex("android:label=\"Clone Fixture \\(copy\\)\"").findAll(xml).count()) // application + the launcher activity
        // the other activity has no label; nothing else got one
        assertEquals(2, Regex("android:label=").findAll(xml).count())
    }

    @Test fun withoutALabelTheNameIsLeftAlone() {
        val r = ok(PackageClone.apply(res("fx_clone_manifest.bin"), null, CloneSpec(old, "forge", null)))
        assertTrue(AxmlDecoder().decode(r.replacements.getValue("AndroidManifest.xml")).contains("android:label=\"@0x"))
        assertFalse(r.replacements.containsKey("resources.arsc"))
    }

    @Test fun theResourceTablePackageNameIsRenamedAndStillReads() {
        val arsc = result.replacements.getValue("resources.arsc")
        assertEquals(listOf("$old.forge"), ArscPackage.packageNames(arsc))
        val before = ArscReader().parse(res("fx_clone.arsc")); val after = ArscReader().parse(arsc)
        assertEquals(before.entries.mapValues { it.value.type + "/" + it.value.name + "=" + it.value.value }, after.entries.mapValues { it.value.type + "/" + it.value.name + "=" + it.value.value })
    }

    @Test fun refusesWhatCannotWork() {
        val shared = PackageClone.apply(res("fx_clone_shared_manifest.bin"), null, spec) as CloneResult.Unsupported
        assertTrue(shared.reason.contains("sharedUserId"))
        assertTrue((PackageClone.apply(res("fx_clone_manifest.bin"), null, CloneSpec("some.other.app", "forge", null)) as CloneResult.Unsupported).reason.contains("does not match"))
        assertTrue(PackageClone.apply(ByteArray(40), null, spec) is CloneResult.Unsupported)
    }

    @Test fun namesAreValidated() {
        assertNull(PackageClone.suffixProblem("forge")); assertNull(PackageClone.suffixProblem("copy2"))
        for (bad in listOf("", "Forge", "2copy", "a b", "a.b", "x".repeat(25), "é")) assertNotNull(bad, PackageClone.suffixProblem(bad))
        assertNull(PackageClone.packageProblem("com.example.app")); assertNotNull(PackageClone.packageProblem("single")); assertNotNull(PackageClone.packageProblem("a..b"))
        assertNull(PackageClone.labelProblem("My App (copy)")); assertNotNull(PackageClone.labelProblem(" ")); assertNotNull(PackageClone.labelProblem("a\nb")); assertNotNull(PackageClone.labelProblem("x".repeat(61)))
        assertTrue(PackageClone.apply(res("fx_clone_manifest.bin"), null, CloneSpec(old, "Bad Suffix", null)) is CloneResult.Unsupported)
    }

    @Test fun arscRenameRules() {
        assertNull(ArscPackage.rename(res("fx_clone.arsc"), "not.there", "x.y"))
        try { ArscPackage.rename(res("fx_clone.arsc"), old, "a".repeat(130)); fail() } catch (_: AxmlException) {}
        try { ArscPackage.rename(ByteArray(30), old, "x.y"); fail() } catch (_: AxmlException) {}
    }

    @Test fun reportsWhatWasRenamedAndWarns() {
        assertTrue(result.renamed.any { it.startsWith("package:") })
        assertTrue(result.notes.any { it.contains("com.other.fixed.authority") })
        assertTrue(result.notes.any { it.contains("sign in again") })
    }

    // ---- the whole thing on an APK: clone -> rebuild -> sign -> verify
    @Test fun clonedApkRebuildsSignsAndVerifies() {
        val apk = tmp.newFile("fx.apk").apply { writeBytes(res("fx_clone.apk")) }
        val prepared = ok(PackageClone.prepare(apk, spec))
        val unsigned = tmp.root.resolve("u.apk"); val signed = tmp.root.resolve("s.apk")
        ApkRebuilder().rebuild(apk, unsigned, prepared.replacements)
        val id = SigningKeys(tmp.root.resolve("keys")).loadOrCreate()
        ApkSigning().sign(unsigned, signed, id, 26)
        val r = ApkChecks().verify(signed, 26)
        assertTrue(r.errors.toString(), r.verified)
        java.util.zip.ZipFile(signed).use { z ->
            val d = AxmlDecoder().decodeFull(z.getInputStream(z.getEntry("AndroidManifest.xml")).readBytes())
            assertEquals("$old.forge", d.rootAttributes["package"])
            assertEquals(listOf("$old.forge"), ArscPackage.packageNames(z.getInputStream(z.getEntry("resources.arsc")).readBytes()))
            assertTrue(ArscReader().read(signed) is ResourceResult.Ok)
        }
        assertEquals(0L, ZipLayout.read(signed).getValue("resources.arsc").dataOffset % 4)
    }

    @Test fun cloningCombinesWithAnImageReplacement() {
        val apk = tmp.newFile("fx2.apk").apply { writeBytes(res("fx_img.apk")) }
        // fx_img has its own package; clone it and replace its image in the same rebuild
        val spec2 = CloneSpec("com.example.imgfx", "forge", "Image Copy")
        val prepared = ok(PackageClone.prepare(apk, spec2))
        val all = prepared.replacements + mapOf("res/drawable/logo.png" to res("new_logo.png"))
        val out = tmp.root.resolve("o.apk")
        ApkRebuilder().rebuild(apk, out, all)
        java.util.zip.ZipFile(out).use { z -> assertArrayEquals(res("new_logo.png"), z.getInputStream(z.getEntry("res/drawable/logo.png")).readBytes()) }
    }
}
