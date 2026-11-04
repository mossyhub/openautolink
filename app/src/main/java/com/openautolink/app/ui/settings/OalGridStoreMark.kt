package com.openautolink.app.ui.settings

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import com.openautolink.app.R

/** Exact current store art, isolated from the Ring0 launcher BETA overlay. No replacement artwork.
 * Only its green icon matte is removed; every retained RGB pixel is copied unchanged. */
internal object OalGridStoreMark {
    fun read(context: Context): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true; inScaled = false }
        BitmapFactory.decodeResource(context.resources, R.drawable.oal_grid_store_art, bounds)
        if (bounds.outWidth !in 1..1024 || bounds.outHeight !in 1..1024) return@runCatching null
        val source = BitmapFactory.decodeResource(context.resources, R.drawable.oal_grid_store_art,
            BitmapFactory.Options().apply { inScaled = false; inPreferredConfig = Bitmap.Config.ARGB_8888 })
            ?: return@runCatching null
        try {
            val width = source.width; val height = source.height
            val pixels = IntArray(width * height)
            source.getPixels(pixels, 0, width, 0, 0, width, height)
            var left = width; var top = height; var right = 0; var bottom = 0
            for (y in 0 until height) for (x in 0 until width) {
                val index = y * width + x; val pixel = pixels[index]
                val r = Color.red(pixel); val g = Color.green(pixel); val b = Color.blue(pixel)
                val mark = (r >= 220 && g >= 220 && b >= 220) || (b > g + 12 && b > r + 15)
                if (mark) {
                    left = minOf(left, x); top = minOf(top, y); right = maxOf(right, x + 1); bottom = maxOf(bottom, y + 1)
                } else pixels[index] = Color.TRANSPARENT
            }
            if (right <= left || bottom <= top) return@runCatching null
            Bitmap.createBitmap(pixels, top * width + left, width, right - left, bottom - top, Bitmap.Config.ARGB_8888)
        } finally { source.recycle() }
    }.getOrNull()
}
