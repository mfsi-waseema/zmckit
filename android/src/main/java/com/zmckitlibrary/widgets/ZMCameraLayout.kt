package com.zmckitlibrary.widgets

import android.content.Context
import android.net.Uri
import android.util.AttributeSet
import android.view.KeyEvent
import android.widget.Toast
import androidx.constraintlayout.widget.ConstraintLayout
import com.snap.camerakit.Session
import com.snap.camerakit.lenses.LensesComponent
import com.snap.camerakit.lenses.whenHasSome
import com.zmckitlibrary.R
import com.zmckitlibrary.ZMCKitManager
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

class ZMCameraLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ConstraintLayout(context, attrs, defStyleAttr) {

    private lateinit var snapCameraLayout: SnapCameraLayout
    private val closeOnDestroy = mutableListOf<Closeable>()

    // Private initialize method
    private fun initialize(
        apiToken: String?,
        cameraFacingFront: Boolean = false,
        lensGroupIds: Set<String>,
        applyLensById: String?,
        cameraListener: ZMCKitManager.ZMCameraListener?
    ) {
        inflate(context, R.layout.snap_camera_layput, this)

        snapCameraLayout = findViewById<SnapCameraLayout>(R.id.snap_camera_layout).apply {
            configureSession {
                apiToken(apiToken)
            }

            onSessionAvailable { session ->
                handleSessionAvailable(
                    session, lensGroupIds,
                    applyLensById, cameraFacingFront
                )
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

            onAppliedLens { lensId ->
                cameraListener?.onLensChange(lensId)
            }

            onError { exception ->
                exception.message?.let {
                    Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun handleSessionAvailable(
        session: Session,
        lensGroupIds: Set<String>,
        applyLensById: String?,
        cameraFacingFront: Boolean
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
                    } else {
                        requireActivity().runOnUiThread {
                            snapCameraLayout.setupCarousel(lenses)
                        }

                        lenses.first().let {
                            snapCameraLayout.applyLens(it)
                        }
                    }
                }
            }
        )
        snapCameraLayout.startPreview(facingFront = cameraFacingFront)
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
        return if (snapCameraLayout.dispatchKeyEvent(event)) {
            true
        } else {
            super.dispatchKeyEvent(event)
        }
    }
}