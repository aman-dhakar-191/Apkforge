package com.amandhakar.apkforge

import com.amandhakar.apkforge.build.*
import org.junit.Assert.*
import org.junit.Test
import java.util.zip.ZipFile

class AuthorityCloneTest {
    private val apk get() = java.io.File(javaClass.getResource("/fx_authority.apk")!!.toURI())

    @Test fun anAuthorityThatIsAResourceIsRenamedInEveryLanguage() {
        val r = PackageClone.prepare(apk, CloneSpec("com.example.authfx", "forge", null))
        r as CloneResult.Ok
        val arsc = r.replacements.getValue("resources.arsc")
        val ids = (0x7f010000..0x7f010001)
        val texts = ids.flatMap { i -> ArscEditor.slots(arsc, i) }.mapNotNull { it.text }
        assertTrue("both languages are renamed: $texts", texts.size >= 2 && texts.all { it == "com.example.authfx.forge.AssetProvider" })
        assertTrue(r.renamed.any { it.contains("authority") })
    }

    @Test fun anUnreadableResourceAuthorityIsRefusedInsteadOfProducingAClash() {
        val manifest = ZipFile(apk).use { z -> z.getInputStream(z.getEntry("AndroidManifest.xml")).readBytes() }
        val r = PackageClone.apply(manifest, null, CloneSpec("com.example.authfx", "forge", null))
        assertTrue(r is CloneResult.Unsupported)
    }
}
