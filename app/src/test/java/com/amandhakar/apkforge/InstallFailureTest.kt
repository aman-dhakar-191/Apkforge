package com.amandhakar.apkforge

import com.amandhakar.apkforge.build.InstallFailure
import org.junit.Assert.*
import org.junit.Test

class InstallFailureTest {
    @Test fun findsAndroidsOwnCode() {
        assertEquals("INSTALL_FAILED_DUPLICATE_PERMISSION", InstallFailure.token("INSTALL_FAILED_DUPLICATE_PERMISSION: Package com.x attempting to redeclare permission vendor.p already owned by com.y"))
        assertNull(InstallFailure.token("something else")); assertNull(InstallFailure.token(null))
    }

    @Test fun explainsWhatAndroidSaidAndKeepsItsWords() {
        val m = "INSTALL_FAILED_CONFLICTING_PROVIDER: Can't install because provider name a.b (in package c.d) is already used by e.f"
        val e = InstallFailure.explain(5, m)
        assertTrue(e, e.contains("conflicts with something already installed"))
        assertTrue(e.contains("content-provider name"))
        assertTrue(e.contains(m)) // Android's own text is always shown
        assertTrue(e.contains("(status 5)"))
    }

    @Test fun neverBlamesTheSignatureUnlessAndroidSaidSo() {
        for (status in listOf(5, 7)) {
            val e = InstallFailure.explain(status, "INSTALL_FAILED_DUPLICATE_PERMISSION: x")
            assertFalse(e, e.contains("different key") || e.contains("signed with"))
        }
        assertTrue(InstallFailure.explain(5, "INSTALL_FAILED_UPDATE_INCOMPATIBLE: x").contains("different signature"))
    }

    @Test fun unknownAndMissingMessagesAreHonest() {
        val e = InstallFailure.explain(7, null)
        assertTrue(e.contains("not compatible") && e.contains("Android gave no detail"))
        val u = InstallFailure.explain(1, "INSTALL_FAILED_SOMETHING_NEW: ?")
        assertTrue(u.contains("INSTALL_FAILED_SOMETHING_NEW")) // shown as Android said it, no invented cause
        assertTrue(InstallFailure.explain(2, "blocked").contains("Play Protect"))
        assertTrue(InstallFailure.explain(3, null).contains("cancelled"))
    }
}
