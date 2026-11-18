package com.wanderwildwood.aikotoba.ui

import com.ok1cdj.kauth.core.VaultFormatException
import com.wanderwildwood.aikotoba.R
import com.wanderwildwood.aikotoba.vault.NativeKdf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class KauthFailureTest {
    @Test
    fun `a backup this app cannot read says so in the app's words`() {
        assertEquals(R.string.kauth_unreadable, kauthFailure(VaultFormatException("unsupported vault version 9")))
        assertEquals(R.string.kauth_unreadable, kauthFailure(IllegalArgumentException("bad salt")))
    }

    @Test
    fun `a backup asking for too much work says that`() {
        assertEquals(R.string.kauth_too_costly, kauthFailure(NativeKdf.TooCostly()))
    }
}
