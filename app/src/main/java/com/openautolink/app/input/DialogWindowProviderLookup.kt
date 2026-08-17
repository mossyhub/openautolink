package com.openautolink.app.input

import android.view.View
import androidx.compose.ui.window.DialogWindowProvider

/** Finds the Compose dialog window owner, including the dialog root itself. */
internal fun findDialogWindowProvider(start: Any?): DialogWindowProvider? {
    var node = start
    while (node != null) {
        if (node is DialogWindowProvider) return node
        node = (node as? View)?.parent
    }
    return null
}
