@file:JvmName("Contexts")

package com.zmckitlibrary.widgets

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import android.view.View
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.google.ar.core.ArCoreApk
import java.io.File
import java.lang.Exception
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

private const val TAG = "Contexts"

/**
 * Checks whether ArCore is available for this [Context] ClassLoader.
 *
 */
val Context.arCoreAvailable: Boolean get() {
    return try {
        javaClass.classLoader?.loadClass(ArCoreApk::class.java.name) != null
    } catch (e: LinkageError) {
        false
    } catch (e: ClassNotFoundException) {
        false
    }
}

/**
 * Checks whether this [Context] supports use of ArCore.
 *
 */
val Context.arCoreSupportedAndInstalled: Boolean get() {
    return try {
        ArCoreApk.getInstance()
            .checkAvailability(applicationContext) == ArCoreApk.Availability.SUPPORTED_INSTALLED
    } catch (e: LinkageError) {
        false
    }
}

/**
 * Uses [ArCoreApk.checkAvailability] to repeatedly check AR Core availability with an [interval] until availability is
 * not equal to [ArCoreApk.Availability.UNKNOWN_CHECKING] or [timeout] is reached, then notifies [callback] with
 * an obtained availability.
 *
 */
fun Context.checkArCoreAvailability(
    executor: ScheduledExecutorService,
    interval: Long = 200L,
    intervalTimeUnit: TimeUnit = TimeUnit.MILLISECONDS,
    timeout: Long = 2L,
    timeoutTimeUnit: TimeUnit = TimeUnit.SECONDS,
    callback: (ArCoreApk.Availability) -> Unit
) {
    val startTime = SystemClock.elapsedRealtimeNanos()
    val checkArCoreAvailability = object : Runnable {
        override fun run() {
            val availability = ArCoreApk.getInstance().checkAvailability(applicationContext)
            if (availability == ArCoreApk.Availability.UNKNOWN_CHECKING && timeoutTimeUnit.convert(
                    SystemClock.elapsedRealtimeNanos() - startTime, TimeUnit.NANOSECONDS
                ) < timeout
            ) {
                executor.schedule(this, interval, intervalTimeUnit)
            } else {
                callback(availability)
            }
        }
    }
    executor.execute(checkArCoreAvailability)
}

/**
 * Blocks execution until AR Core availability is obtained via [checkArCoreAvailability] call.
 *
 */
fun Context.blockingCheckArCoreAvailability(
    executor: ScheduledExecutorService,
    interval: Long = 200L,
    intervalTimeUnit: TimeUnit = TimeUnit.MILLISECONDS,
    timeout: Long = 5L,
    timeoutTimeUnit: TimeUnit = TimeUnit.SECONDS,
): ArCoreApk.Availability {
    val countDownLatch = CountDownLatch(1)
    val availability = AtomicReference<ArCoreApk.Availability>()
    checkArCoreAvailability(executor, interval, intervalTimeUnit, timeout, timeoutTimeUnit) {
        availability.set(it)
        countDownLatch.countDown()
    }
    countDownLatch.await()
    return availability.get()
}

/**
 * Tries to install AR Core if supported by the device. Returns true if AR Core is installed, False otherwise.
 *
 */
fun Context.tryToInstallArCore(): Boolean {
    return if (this is Activity && this is LifecycleOwner) {
        val activity = this as Activity
        var installed = false
        val latch = CountDownLatch(1)
        try {
            when (
                ArCoreApk.getInstance().requestInstall(
                    activity,
                    false,
                    ArCoreApk.InstallBehavior.OPTIONAL,
                    ArCoreApk.UserMessageType.FEATURE
                )
            ) {
                ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                    activity.runOnUiThread {
                        (this as LifecycleOwner).takeIf { it.lifecycle.currentState != Lifecycle.State.DESTROYED }
                            ?.lifecycle
                            ?.addObserver(object : DefaultLifecycleObserver {

                                override fun onResume(owner: LifecycleOwner) {
                                    super.onResume(owner)
                                    installed = arCoreSupportedAndInstalled
                                    owner.lifecycle.removeObserver(this)
                                    latch.countDown()
                                }

                                override fun onDestroy(owner: LifecycleOwner) {
                                    super.onDestroy(owner)
                                    owner.lifecycle.removeObserver(this)
                                    latch.countDown()
                                }
                            })
                            ?: latch.countDown()
                    }
                }

                ArCoreApk.InstallStatus.INSTALLED -> {
                    installed = true
                    latch.countDown()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Exception while trying to install AR Core", e)
            latch.countDown()
        }
        latch.await()
        installed
    } else {
        false
    }
}

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