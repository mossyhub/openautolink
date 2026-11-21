package com.openautolink.app.drivecard

import android.content.Context
import android.graphics.Typeface

/** Resource access only: no foreign package code/context/service and no copied OEM font asset.
 * This current-vehicle Android UI font is not claimed to be the native QNX instrument font. */
internal object DriveCardTypeface {
    private const val GM_PACKAGE="chevrolet.vcd_chevy_ff.systemui"
    fun load(context: Context): Typeface = runCatching {
        val resources=context.packageManager.getResourcesForApplication(GM_PACKAGE)
        val id=resources.getIdentifier("gmsansui_regular","font",GM_PACKAGE)
        if(id==0)null else resources.getFont(id)
    }.getOrNull() ?: Typeface.create("sans-serif",Typeface.NORMAL)
}
