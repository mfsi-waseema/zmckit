@file:JvmName("Contexts")

package com.zmckitlibrary.widgets

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.view.View
import java.io.File
import java.util.UUID

private const val TAG = "Contexts"

/**
 * Attempts to find an [Activity] that this view is attached to, throws if it can't find one.
 */
internal fun View.requireActivity(): Activity {
    var context: Context = context
    while (context is ContextWrapper) {
        if (context is Activity) {
            return context
        }
        context = (context).baseContext
    }
    throw IllegalStateException("Could not find an Activity required to host this view: $this")
}

/**
 * Saves the provided [bitmap] as a jpeg file to application's cache directory.
 */
internal fun Context.cacheJpegOf(bitmap: Bitmap): File {
    return File(cacheDir, "${UUID.randomUUID()}.jpg").also {
        it.outputStream().use { outputStream ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
        }
    }
}