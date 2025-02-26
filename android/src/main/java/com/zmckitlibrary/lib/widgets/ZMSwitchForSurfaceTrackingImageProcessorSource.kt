package com.zmckitlibrary.lib.widgets

import android.os.Handler
import android.os.Looper
import com.snap.camerakit.ImageProcessor
import com.snap.camerakit.Source
import com.snap.camerakit.common.Consumer
import com.snap.camerakit.support.arcore.ArCoreImageProcessorSource
import java.io.Closeable
import java.util.concurrent.atomic.AtomicReference

/**
 * A simple implementation of [Source] for [ImageProcessor] which uses [ImageProcessor.observeRequiredCapabilities]
 * method to attach different camera sources when requirements changed.
 *
 * @param defaultCameraSource The default source to be used when no additional capabilities are required.
 * @param surfaceTrackingSourceProvider Provides a [Source] (if available) which can provide surface tracking data and
 * depth images. Returns null otherwise. Currently, only the [ArCoreImageProcessorSource] can be used as a
 * [surfaceTrackingSource].
 * @param onSourceAttached The consumer which is notified once attached source is changed.
 * @param callbackHandler The handler on which [onSourceAttached] consumer is invoked.
 *
 * @since 1.17.0
 */
class ZMSwitchForSurfaceTrackingImageProcessorSource(
    private val defaultCameraSource: Source<ImageProcessor>,
    private val surfaceTrackingSourceProvider: () -> Source<ImageProcessor>?,
    private val onSourceAttached: Consumer<Source<ImageProcessor>>,
    private val callbackHandler: Handler = Handler(Looper.getMainLooper())
) : Source<ImageProcessor> {

    private val surfaceTrackingSource by lazy { surfaceTrackingSourceProvider() }
    private val attachedSourceCloseable = AtomicReference<Closeable>()
    private val lastImageProcessor = AtomicReference<ImageProcessor>()
    private val attachedSourceReference = AtomicReference<Source<ImageProcessor>>()
    private var sourceAttachedRunnable: Runnable? = null

    override fun attach(processor: ImageProcessor): Closeable {
        lastImageProcessor.set(processor)
        val capabilitiesSubscription = processor.observeRequiredCapabilities { capabilities ->
            if (capabilities.isEmpty()) {
                switchSource(defaultCameraSource)
            } else if (capabilities.contains(ImageProcessor.Input.Capability.SURFACE_TRACKING)) {
                surfaceTrackingSource?.also { source ->
                    switchSource(source)
                }
            }
        }
        return Closeable {
            if (lastImageProcessor.compareAndSet(processor, null)) {
                sourceAttachedRunnable?.let { callbackHandler.removeCallbacks(it) }
                capabilitiesSubscription.close()
                attachedSourceCloseable.getAndSet(null)?.close()
            } else {
                throw IllegalStateException("Unexpected ImageProcessor set before it was cleared")
            }
        }
    }

    private fun switchSource(source: Source<ImageProcessor>) {
        callbackHandler.run {
            sourceAttachedRunnable?.let { removeCallbacks(it) }
            val sourceAttachable = Runnable {
                lastImageProcessor.get()?.let { processor ->
                    if (attachedSourceReference.getAndSet(source) != source) {
                        attachedSourceCloseable.getAndSet(source.attach(processor))?.close()
                        onSourceAttached.accept(attachedSourceReference.get())
                    }
                }
            }
            sourceAttachedRunnable = sourceAttachable
            post(sourceAttachable)
        }
    }
}
