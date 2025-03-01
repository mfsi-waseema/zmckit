package com.zmckitlibrary

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import com.zmckitlibrary.widgets.ZMCameraLayout
import com.zmckitlibrary.camera.Constants.EXTRA_IMAGE_URI
import com.zmckitlibrary.camera.ImagePreviewActivity

class ZMCKitManager private constructor() {

    // Notify listeners when lens changes
    interface ZMCameraListener : java.io.Serializable {
        // Required method to handle image capture
        fun onImageCaptured(imageUri: Uri)

        // Required method to handle lens change
        fun onLensChange(lensId: String)

        // Required method to handle show preview
        fun shouldShowDefaultPreview(): Boolean
    }

    companion object {
        /**
         * Initializes and configures a ZMCCameraLayout for single product view.
         *
         * @param context the context to be used for creating the ZMCCameraLayout.
         * @param snapAPIToken the Snap API token for configuration.
         * @param partnerGroupId the group ID for the partner.
         * @param lensId the lens ID to be applied.
         * @param cameraListener triggered when lens changes and image capture.
         * @return the configured ZMCCameraLayout.
         */
        fun createProductCameraLayout(
            context: Context,
            snapAPIToken: String,
            partnerGroupId: String,
            lensId: String,
            cameraFacingFront: Boolean,
            cameraListener: ZMCameraListener?
        ): ZMCameraLayout {
            // Initialize the ZMCCameraLayout programmatically
            val zmcCameraLayout = ZMCameraLayout(context).apply {
                // Configure it for Single Product
                configureProductViewLayout(
                    snapAPIToken,
                    partnerGroupId,
                    lensId,
                    cameraFacingFront = cameraFacingFront,
                    cameraListener = cameraListener
                )
            }
            return zmcCameraLayout
        }

        /**
         * Initializes and configures a ZMCCameraLayout for group view.
         *
         * @param context the context to be used for creating the ZMCCameraLayout.
         * @param snapAPIToken the Snap API token for configuration.
         * @param partnerGroupId the group ID for the partner.
         * @param cameraListener triggered when lens changes and image capture.
         * @return the configured ZMCCameraLayout.
         */
        fun createGroupCameraLayout(
            context: Context,
            snapAPIToken: String,
            partnerGroupId: String,
            cameraFacingFront: Boolean,
            cameraListener: ZMCameraListener?
        ): ZMCameraLayout {
            // Initialize the ZMCCameraLayout programmatically
            val zmcCameraLayout = ZMCameraLayout(context).apply {
                // Configure it for Group View
                configureGroupViewLayout(
                    snapAPIToken,
                    partnerGroupId,
                    cameraFacingFront = cameraFacingFront,
                    cameraListener = cameraListener
                )
            }
            return zmcCameraLayout
        }

        fun showPreview(context: Context, imagePath: String) {
            val previewIntent = Intent(context, ImagePreviewActivity::class.java).apply {
                putExtra(EXTRA_IMAGE_URI, imagePath)
            }
            when {
                context is Activity -> {
                    // Directly use the activity context
                    context.startActivity(previewIntent)
                }
                context is ContextWrapper && (context.baseContext is Activity) -> {
                    // Handle cases where the context is wrapped (e.g., ContextThemeWrapper)
                    (context.baseContext as Activity).startActivity(previewIntent)
                }
            }
        }
    }
}
