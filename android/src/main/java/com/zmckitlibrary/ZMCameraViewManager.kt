package com.zmckitlibrary

import android.net.Uri
import android.view.Choreographer
import android.view.View
import android.widget.FrameLayout
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.uimanager.ThemedReactContext
import com.facebook.react.uimanager.ViewGroupManager
import com.facebook.react.uimanager.annotations.ReactProp
import com.facebook.react.modules.core.DeviceEventManagerModule
import com.zmckitlibrary.widgets.ZMCameraLayout

class ZMCameraViewManager(private val reactContext: ReactApplicationContext) : ViewGroupManager<FrameLayout>() {

    private var apiToken: String? = null
    private var lensId: String? = null
    private var groupId: String? = null
    private var singleLens: Boolean = true
    private var showPreview: Boolean = true
    private var showFrontCamera: Boolean = false

    private var cameraLayout: ZMCameraLayout? = null

    override fun getName() = "ZMCameraView"

    override fun createViewInstance(context: ThemedReactContext): FrameLayout {
        return FrameLayout(context)
    }

    @ReactProp(name = "apiToken")
    fun setApiToken(view: FrameLayout, apiToken: String) {
        this.apiToken = apiToken
        initializeCamera(view)
    }

    @ReactProp(name = "lensId")
    fun setLensId(view: FrameLayout, lensId: String) {
        this.lensId = lensId
        initializeCamera(view)
    }

    @ReactProp(name = "groupId")
    fun setGroupId(view: FrameLayout, groupId: String) {
        this.groupId = groupId
        initializeCamera(view)
    }

    @ReactProp(name = "singleLens")
    fun setSingleLens(view: FrameLayout, singleLens: Boolean) {
        this.singleLens = singleLens
    }

    @ReactProp(name = "showPreview")
    fun setShowPreview(view: FrameLayout, showPreview: Boolean) {
        this.showPreview = showPreview
    }

    @ReactProp(name = "showFrontCamera")
    fun setShowFrontCamera(view: FrameLayout, showFrontCamera: Boolean) {
        this.showFrontCamera = showFrontCamera
    }

    private fun initializeCamera(view: FrameLayout) {
        // Validate required properties
        if (singleLens) {
            if (apiToken.isNullOrEmpty() || lensId.isNullOrEmpty() || groupId.isNullOrEmpty()) {
                return
            }
        } else {
            if (apiToken.isNullOrEmpty() || groupId.isNullOrEmpty()) {
                return
            }
        }

        // Clean up existing camera layout if it exists
        cameraLayout?.apply {
            removeAllViews()
            cameraLayout = null
        }

        // Ensure lifecycle owner is available
        val lifecycleOwner = reactContext.currentActivity as? FragmentActivity
            ?: throw IllegalStateException("React Native Activity must be a FragmentActivity")

        // Initialize the camera layout based on the lens type (singleLens or group)
        cameraLayout = if (singleLens) {
            createSingleLensCameraLayout(lifecycleOwner)
        } else {
            createGroupLensCameraLayout(lifecycleOwner)
        }

        // Add lifecycle observer for cleanup
        lifecycleOwner.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                // Cleanup camera layout when the activity is destroyed
                cameraLayout?.removeAllViews()
                cameraLayout = null
            }
        })

        // Add the camera layout to the view
        cameraLayout?.let {
            it.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            view.addView(it)
            setupLayout(it)
        }
    }

    // Create camera layout for single lens
    private fun createSingleLensCameraLayout(lifecycleOwner: FragmentActivity): ZMCameraLayout {
        return ZMCKitManager.createProductCameraLayout(
            context = lifecycleOwner,
            snapAPIToken = apiToken!!,
            partnerGroupId = groupId!!,
            lensId = lensId!!,
            cameraFacingFront = showFrontCamera,
            cameraListener = object : ZMCKitManager.ZMCameraListener {
                override fun onImageCaptured(imageUri: Uri) {
                    // Emit captured image event to React Native
                    val params = Arguments.createMap()
                    params.putString("imageUri", imageUri.toString())
                    sendEvent("onImageCaptured", params)
                }

                override fun onLensChange(lensId: String) {
                    // Emit lens change event to React Native
                    val params = Arguments.createMap()
                    params.putString("lensId", lensId)
                    sendEvent("onLensChange", params)
                }

                override fun shouldShowDefaultPreview(): Boolean {
                    return showPreview
                }
            }
        )
    }

    // Create camera layout for group lens
    private fun createGroupLensCameraLayout(lifecycleOwner: FragmentActivity): ZMCameraLayout {
        return ZMCKitManager.createGroupCameraLayout(
            context = lifecycleOwner,
            snapAPIToken = apiToken!!,
            partnerGroupId = groupId!!,
            cameraFacingFront = showFrontCamera,
            cameraListener = object : ZMCKitManager.ZMCameraListener {
                override fun onImageCaptured(imageUri: Uri) {
                    // Emit captured image event to React Native
                    val params = Arguments.createMap()
                    params.putString("imageUri", imageUri.toString())
                    sendEvent("onImageCaptured", params)
                }

                override fun onLensChange(lensId: String) {
                    // Emit lens change event to React Native
                    val params = Arguments.createMap()
                    params.putString("lensId", lensId)
                    sendEvent("onLensChange", params)
                }

                override fun shouldShowDefaultPreview(): Boolean {
                    return showPreview
                }
            }
        )
    }

    private fun sendEvent(eventName: String, params: Any?) {
        reactContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
            .emit(eventName, params)
    }

    private fun setupLayout(view: View) {
        Choreographer.getInstance().postFrameCallback(object: Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                manuallyLayoutChildren(view)
                view.viewTreeObserver.dispatchOnGlobalLayout()
                Choreographer.getInstance().postFrameCallback(this)
            }
        })
    }

    private fun manuallyLayoutChildren(view: View) {
        val parentView = view.parent as? View ?: return
        val width = parentView.width
        val height = parentView.height

        if (width > 0 && height > 0) {
            view.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
            )
            view.layout(0, 0, width, height)
        }
    }
}
