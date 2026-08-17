package com.openautolink.app.input

import androidx.compose.ui.window.DialogWindowProvider
import io.mockk.mockk
import org.junit.Assert.assertSame
import org.junit.Test

class DialogWindowProviderLookupTest {

    @Test
    fun `dialog root provider is found before walking to its parent`() {
        val provider = mockk<DialogWindowProvider>()

        assertSame(provider, findDialogWindowProvider(provider))
    }
}
