package com.zmckitlibrary.lib.widgets

import android.content.Context
import android.net.Uri
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import androidx.constraintlayout.widget.ConstraintLayout
import com.snap.camerakit.ImageProcessor
import com.snap.camerakit.UnauthorizedApplicationException
import com.snap.camerakit.lenses.LensesComponent
import com.snap.camerakit.lenses.whenHasSome
import com.zmckitlibrary.R
import com.zmckitlibrary.lib.ZMCKitManager
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

class ZMCameraLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ConstraintLayout(context, attrs, defStyleAttr) {

    private lateinit var ZCameraLayout: ZCameraLayout
    private var cameraSession: com.snap.camerakit.Session? = null
    private val closeOnDestroy = mutableListOf<Closeable>()

    // Private initialize method
    private fun initialize(
        apiToken: String?,
        cameraFacingFront: Boolean = false,
        lensGroupIds: Set<String>,
        applyLensById: String?,
        cameraListener: ZMCKitManager.ZMCameraListener?
    ) {
        inflate(context, R.layout.camera_layout, this)

        ZCameraLayout = findViewById<ZCameraLayout>(R.id.snap_camera_layout).apply {
            configureSession { apiToken(apiToken) }

            configureLensesCarousel {
                observedGroupIds = lensGroupIds
                closeButtonEnabled = false
                disableIdle = true

                applyLensById?.let {
                    configureEachItem { item ->
                        item.enabled = item.lens.id == applyLensById
                    }
                    enabled = false
                }
            }

            captureButton.visibility = View.VISIBLE

            onSessionAvailable { session ->
                cameraSession = session
                handleSessionAvailable(session, lensGroupIds, applyLensById, cameraFacingFront, cameraListener)
            }

            onImageTaken { bitmap ->
                try {
                    val imageFile = context.cacheJpegOf(bitmap)
                    cameraListener?.onImageCaptured(Uri.fromFile(imageFile))
                    if (cameraListener?.shouldShowDefaultPreview() == true) {
                        ZMCKitManager.showPreview(context, imageFile.absolutePath)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            onError { error ->
                val exception = mapErrorToException(error)
                Toast.makeText(context, exception.message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun handleSessionAvailable(
        session: com.snap.camerakit.Session,
        lensGroupIds: Set<String>,
        applyLensById: String?,
        cameraFacingFront: Boolean,
        listener: ZMCKitManager.ZMCameraListener?
    ) {
        val appliedLensById = AtomicBoolean()
        closeOnDestroy.add(
            session.lenses.repository.observe(
                LensesComponent.Repository.QueryCriteria.Available(lensGroupIds)
            ) { result ->
                result.whenHasSome { lenses ->
                    if (!applyLensById.isNullOrEmpty()) {
                        lenses.find { lens -> lens.id == applyLensById }?.let { lens ->
                            if (appliedLensById.compareAndSet(false, true)) {
                                session.lenses.processor.apply(
                                    lens, LensesComponent.Lens.LaunchData.Empty
                                )
                            }
                        }
                    }
                }
            }
        )

        // Observe carousel events and notify when the lens is selected
        closeOnDestroy.add(
            session.lenses.carousel.observe { event ->
                when (event) {
                    is LensesComponent.Carousel.Event.Activated.WithLens -> {
                        listener?.onLensChange(event.lens.id) // Notify about the selected lens
                    }
                    else -> {
                        // Handle other carousel events if needed
                    }
                }
            }
        )
        ZCameraLayout.startPreview(facingFront = cameraFacingFront)
    }

    private fun mapErrorToException(error: Throwable): Exception {
        return when (error) {
            is UnauthorizedApplicationException ->
                IllegalStateException("Application is not authorized to use CameraKit", error)
            is ZCameraLayout.Failure.DeviceNotSupported ->
                UnsupportedOperationException("Device not supported by CameraKit", error)
            is ZCameraLayout.Failure.MissingPermissions ->
                SecurityException(
                        "Camera permissions not granted. Please enable them in settings.")
            is ImageProcessor.Failure.Graphics ->
                RuntimeException("Graphics processing failure in CameraKit", error)
            is LensesComponent.Processor.Failure ->
                RuntimeException("Lenses processing failure in CameraKit", error)
            else -> Exception("Unexpected error in CameraKit", error)
        }
    }

    // Public function to launch the camera in product view mode (single lens mode)
    fun configureProductViewLayout(
        snapAPIToken: String,
        partnerGroupId: String,
        lensId: String,
        cameraFacingFront: Boolean = false,
        cameraListener: ZMCKitManager.ZMCameraListener?
    ) {
        initialize(
            apiToken = snapAPIToken,
            cameraFacingFront = cameraFacingFront,
            lensGroupIds = setOf(partnerGroupId),
            applyLensById = lensId,
            cameraListener = cameraListener
        )
    }

    // Public function to launch the camera in group view mode (lens group mode)
    fun configureGroupViewLayout(
        snapAPIToken: String,
        partnerGroupId: String,
        cameraFacingFront: Boolean = false,
        cameraListener: ZMCKitManager.ZMCameraListener?
    ) {
        initialize(
            apiToken = snapAPIToken,
            cameraFacingFront = cameraFacingFront,
            lensGroupIds = setOf(partnerGroupId),
            applyLensById = null,
            cameraListener = cameraListener
        )
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        closeOnDestroy.forEach { closeable ->
            closeable.close()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        return if (ZCameraLayout.dispatchKeyEvent(event)) {
            true
        } else {
            super.dispatchKeyEvent(event)
        }
    }
}