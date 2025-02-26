package com.zmckitlibrary.lib.widgets

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcel
import android.os.Parcelable
import android.util.AttributeSet
import android.util.Log
import android.util.Rational
import android.view.KeyEvent
import android.view.View
import android.view.ViewStub
import androidx.annotation.MainThread
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.customview.view.AbsSavedState
import androidx.lifecycle.LifecycleOwner
import com.google.ar.core.ArCoreApk
import com.snap.camerakit.ImageProcessor
import com.snap.camerakit.SafeRenderAreaProcessor
import com.snap.camerakit.Session
import com.snap.camerakit.Source
import com.snap.camerakit.common.Consumer
import com.snap.camerakit.configureLenses
import com.snap.camerakit.invoke
import com.snap.camerakit.lenses.LensesComponent
import com.snap.camerakit.lenses.configureCache
import com.snap.camerakit.lenses.configureCarousel
import com.snap.camerakit.lenses.configureHints
import com.snap.camerakit.lenses.observe
import com.snap.camerakit.lenses.whenActivated
import com.snap.camerakit.lenses.whenActivatedIdle
import com.snap.camerakit.lenses.whenActivatedWithLens
import com.snap.camerakit.lenses.whenApplied
import com.snap.camerakit.lenses.whenDeactivated
import com.snap.camerakit.lenses.whenHasSome
import com.snap.camerakit.lenses.whenIdle
import com.snap.camerakit.support.arcore.ArCoreImageProcessorSource
import com.snap.camerakit.support.camera.AllowsCameraPreview
import com.snap.camerakit.support.camera.AllowsPhotoCapture
import com.snap.camerakit.support.camera.AllowsSnapshotCapture
import com.snap.camerakit.support.camera.AspectRatio
import com.snap.camerakit.support.camera.Crop
import com.snap.camerakit.support.camerax.CameraXImageProcessorSource
import com.snap.camerakit.support.permissions.HeadlessFragmentPermissionRequester
import com.snap.camerakit.support.widget.SnapButtonView
import com.snap.camerakit.support.widget.cameralayout.R
import com.snap.camerakit.supported
import java.io.Closeable
import java.lang.ref.WeakReference
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.atomic.AtomicReference

private const val TAG = "CameraLayout"
// 150MB to make sure that some lenses that use large assets such as the ones required for
// 3D body tracking (https://lensstudio.snapchat.com/templates/object/3d-body-tracking) have
// enough cache space to fit alongside other lenses.
private const val DEFAULT_LENSES_CONTENT_CACHE_SIZE_MAX_BYTES = 150L * 1024L * 1024L

private val DEFAULT_REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)

/**
 * A composite [View] which takes care of setting up a CameraKit [Session], native camera and other inputs with defaults
 * to present a camera preview with lenses and other UI elements overlaid on top.
 * While an instance of this class offers a way to customize each CameraKit [Session] by registering for callbacks such
 * as [onSessionAvailable], it is also possible to extend this class and override the protected fields and methods if
 * different behavior is desired.
 *
 * @since 1.7.0
 */
open class ZCameraLayout : ConstraintLayout {

    /**
     * Defines the possible types of exception that may be encountered through the lifecycle of [ZCameraLayout].
     */
    sealed class Failure(override val message: String) : RuntimeException(message) {

        /**
         * Exception for the state when the permissions required to use [ZCameraLayout] could not be obtained.
         * @param permissions An array of the required permissions that could not be obtained.
         */
        class MissingPermissions(message: String, private val permissions: Array<String>) : Failure(message)

        /**
         * Exception for the state when it was determined that CameraKit cannot run on a specific device.
         */
        class DeviceNotSupported(message: String) : Failure(message)
    }

    // Customizable view attributes
    private val rootLayoutRes: Int
    private val lensesCarouselHeightDimenRes: Int
    private val lensesCarouselPaddingTopDimenRes: Int
    private val lensesCarouselPaddingBottomDimenRes: Int
    private val lensesCarouselMarginBottomDimenRes: Int
    private val lensesCarouselCloseButtonMarginBottomDimenRes: Int

    // Implementation views not to be exposed to the user.
    private val cameraKitStub: ViewStub

    // Mutable state values that are persisted through the SavedState.
    private var isSessionCapturing: Boolean = false
    private var cameraFacingFront: Boolean = true
    private var cameraAspectRatio: AspectRatio = AspectRatio.RATIO_16_9
    private var cameraCropToRootViewRatio: Boolean = false
    private var cameraImageCapturePhoto: Boolean = false
    private var cameraMirrorHorizontally: Boolean = false
    private var appliedLensId: String? = null
    private var lensesCarouselActivated: Boolean = true
    private var lensesCarouselObservedGroupIds = emptySet<String>()

    // Transient state.
    private var isAttached = false
    private var instanceRestored = false
    private val closeOnDetach = mutableListOf<Closeable>()
    private var cameraKitSession: Session? = null
    // Use a single Closeable reference for lens download status events
    // to avoid bloating closeOnDetach with Closeable per each lens selection.
    private var lensDownloadStatus = EMPTY_CLOSEABLE

    // User provided callbacks, no-op by default.
    private var onSessionAvailable: (Session) -> Unit = {}
    private var onImageTaken: (Bitmap) -> Unit = {}
    private var sessionConfiguration: Session.Builder.() -> Unit = {}
    private var lensesConfiguration: LensesComponent.Builder.() -> Unit = {}
    private var lensesCarouselConfiguration: LensesComponent.Carousel.Configuration.() -> Unit = {}

    private var onChooseFacingForLens: (LensesComponent.Lens) -> LensesComponent.Lens.Facing? = { null }
    private val onChooseFacingForLensRunnable: AtomicReference<Runnable> = AtomicReference()

    // Re-throwing error handler used by default unless user provides one.
    private val errorHandler = MutableErrorHandler()

    constructor(context: Context) : this(context, null)
    constructor(context: Context, attrs: AttributeSet?) : this(context, attrs, 0)
    constructor(context: Context, attrs: AttributeSet?, attributeSetId: Int) : super(context, attrs, attributeSetId) {
        val array = context.obtainStyledAttributes(attrs, R.styleable.CameraLayout, attributeSetId, 0)
        try {
            rootLayoutRes = array.getResourceId(
                R.styleable.CameraLayout_rootLayout, R.layout.camera_kit_camera_layout
            )
            lensesCarouselHeightDimenRes = array.getResourceId(
                R.styleable.CameraLayout_lensesCarouselHeight, R.dimen.camera_kit_lenses_carousel_height
            )
            lensesCarouselPaddingTopDimenRes = array.getResourceId(
                R.styleable.CameraLayout_lensesCarouselPaddingTop, -1
            )
            lensesCarouselPaddingBottomDimenRes = array.getResourceId(
                R.styleable.CameraLayout_lensesCarouselPaddingBottom, -1
            )
            lensesCarouselMarginBottomDimenRes = array.getResourceId(
                R.styleable.CameraLayout_lensesCarouselMarginBottom, R.dimen.camera_kit_lenses_carousel_bottom_margin
            )
            lensesCarouselCloseButtonMarginBottomDimenRes = array.getResourceId(
                R.styleable.CameraLayout_lensesCarouselCloseButtonMarginBottom,
                R.dimen.camera_kit_lenses_carousel_close_button_margin_bottom
            )
        } finally {
            array.recycle()
        }

        inflate(context, rootLayoutRes, this)

        captureButton = findViewById(R.id.button_capture)
        cameraKitStub = findViewById(R.id.camerakit_stub)

        isMotionEventSplittingEnabled = true
        isSaveEnabled = true
    }

    /**
     * A button that provides ability to capture images or videos using simple tap and press&hold gestures.
     * @see SnapButtonView
     */
    val captureButton: SnapButtonView

    /**
     * Allows to start a preview of a camera that supports the provided options.
     * This method should be called after a configured [Session] is provided via [onSessionAvailable],
     * otherwise the request to start preview will be ignored.
     *
     * @param cropToRootViewRatio defines if preview texture should be cropped before processing to match
     * [ZCameraLayout] view aspect ratio.
     */
    @MainThread
    @JvmOverloads
    fun startPreview(
        facingFront: Boolean = cameraFacingFront,
        mirrorHorizontally: Boolean = cameraMirrorHorizontally,
        aspectRatio: AspectRatio = cameraAspectRatio,
        cropToRootViewRatio: Boolean = cameraCropToRootViewRatio,
        callback: (succeeded: Boolean) -> Unit = {}
    ) {
        cameraFacingFront = facingFront
        cameraMirrorHorizontally = mirrorHorizontally
        cameraAspectRatio = aspectRatio
        cameraCropToRootViewRatio = cropToRootViewRatio
        val options = if (mirrorHorizontally) {
            setOf(ImageProcessor.Input.Option.MirrorFramesHorizontally)
        } else {
            emptySet()
        }
        val cropOption = if (cropToRootViewRatio) {
            Crop.Center(Rational(measuredWidth, measuredHeight))
        } else {
            Crop.None
        }

        // This source is Source.Noop until it gets replaced by one configured for a new Session.
        val configuration = AllowsCameraPreview.Configuration.Default(facingFront, aspectRatio, cropOption)
        (activeImageProcessorSource as? AllowsCameraPreview)?.startPreview(
            configuration, options, callback
        )
    }

    /**
     * Allows to register for a callback when a capture results with an image.
     */
    fun onImageTaken(callback: (Bitmap) -> Unit) {
        onImageTaken = callback
    }

    /**
     * Allows to register for a callback when a [Session] becomes available once all the required permissions and
     * support checks are performed.
     */
    fun onSessionAvailable(callback: (Session) -> Unit) {
        onSessionAvailable = callback
    }

    /**
     * Allows to register for a callback when [ZCameraLayout] or its managed [Session] encounters errors that require
     * explicit handling on user's side.
     * @see ZCameraLayout.Failure
     */
    fun onError(callback: (Throwable) -> Unit) {
        // Posting this to run on after view is attached to avoid closing the error handler Closeable immediately
        // if this method is called before view is attached.
        post {
            errorHandler.use(callback).closeOnDetach()
        }
    }

    /**
     * Allows to register for a callback to modify the [Session.Builder] before a [Session] is created.
     */
    fun configureSession(withConfiguration: Session.Builder.() -> Unit) {
        sessionConfiguration = withConfiguration
    }

    /**
     * Allows to register for a callback to modify the [LensesComponent.Carousel.Configuration] before a [Session]
     * is created.
     */
    fun configureLensesCarousel(withConfiguration: LensesComponent.Carousel.Configuration.() -> Unit) {
        lensesCarouselConfiguration = withConfiguration
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isAttached = true
        HeadlessFragmentPermissionRequester(
            requireActivity(),
            (requiredPermissions).toSet()
        ) { results ->
            if (requiredPermissions.all { results[it] == true }) {
                // We schedule the block below to run on the next frame in order to allow CameraLayout users to provide
                // any necessary configuration callbacks after the view gets attached which may happen immediately after
                // inflation in cases such as Activity being restored from a saved state.
                post {
                    if (isAttached) {
                        if (!onReadyForNewSession()) {
                            errorHandler.accept(Failure.DeviceNotSupported("CameraKit is not supported on this device"))
                        }
                    } else {
                        Log.w(TAG, "Not attached to a window, won't attempt to create a new CameraKit Session")
                    }
                }
            } else {
                errorHandler.accept(
                    Failure.MissingPermissions(
                        "Required permissions were not granted for $TAG to start a new CameraKit Session",
                        requiredPermissions
                    )
                )
            }
        }.closeOnDetach()
    }

    override fun onDetachedFromWindow() {
        isAttached = false
        (activeImageProcessorSource as? AllowsCameraPreview)?.stopPreview()
        closeOnDetach.forEach { closeable ->
            closeable.close()
        }
        closeOnDetach.clear()
        lensDownloadStatus.close()
        cameraKitSession?.close()
        cameraKitSession = null
        super.onDetachedFromWindow()
    }

    private fun Closeable.closeOnDetach() {
        if (isAttached) {
            closeOnDetach.add(this)
        } else {
            Log.w(TAG, "Not attached to a window, closing $this")
            close()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        return if (captureButton.dispatchKeyEvent(event)) {
            true
        } else {
            super.dispatchKeyEvent(event)
        }
    }

    override fun onSaveInstanceState(): Parcelable? {
        return super.onSaveInstanceState()?.let { superState ->
            SavedState(superState).let { savedState ->
                savedState.appliedLensId = appliedLensId
                savedState.lensesCarouselActivated = lensesCarouselActivated
                savedState.cameraFacingFront = cameraFacingFront
                savedState.cameraAspectRatio = cameraAspectRatio
                savedState.cameraImageCapturePhoto = cameraImageCapturePhoto
                savedState.cameraMirrorHorizontally = cameraMirrorHorizontally
                savedState.cameraCropToRootViewRatio = cameraCropToRootViewRatio
                savedState.lensesCarouselObservedGroupIds = lensesCarouselObservedGroupIds.toTypedArray()
                savedState
            }
        }
    }

    override fun onRestoreInstanceState(state: Parcelable) {
        val savedState = state as SavedState
        super.onRestoreInstanceState(savedState.superState)
        appliedLensId = savedState.appliedLensId
        lensesCarouselActivated = savedState.lensesCarouselActivated
        cameraFacingFront = savedState.cameraFacingFront
        cameraAspectRatio = savedState.cameraAspectRatio
        cameraImageCapturePhoto = savedState.cameraImageCapturePhoto
        cameraMirrorHorizontally = savedState.cameraMirrorHorizontally
        cameraCropToRootViewRatio = savedState.cameraCropToRootViewRatio
        lensesCarouselObservedGroupIds = linkedSetOf(*savedState.lensesCarouselObservedGroupIds)
        instanceRestored = true
    }

    /**
     * Attempts to create CameraKit [Session], if supported, based on the current configuration and start a camera
     * preview using the [activeImageProcessorSource].
     */
    private fun onReadyForNewSession(): Boolean {
        return if (!supported(context)) {
            false
        } else {
            cameraKitSession = newSession().also {
                onSessionAvailable(it)
            }
            startPreview()
            true
        }
    }

    /**
     * Creates a new CameraKit [Session] based on the current configuration defaults as well as user provided
     * customization through callbacks such as [configureCarousel].
     */
    private fun newSession(): Session {
        // This block configures and creates a new CameraKit instance that is the main entry point to all its features.
        // The CameraKit instance must be closed when appropriate to avoid leaking any resources.
        return Session(context) {
            imageProcessorSource(imageProcessorSource)
            safeRenderAreaProcessorSource(safeRenderAreaProcessorSource)
            handleErrorsWith(errorHandler)
            // The provided ViewStub will be used to inflate CameraKit's Session view hierarchy to handle touch events
            // as well as to render camera preview. In this example we set withPreview to true to have Session
            // render the camera preview - this might not be suitable for other use cases that manage camera preview
            // differently (SurfaceView, off-screen rendering) therefore it is possible to pass withPreview = false
            // and attach camera preview output separately.
            attachTo(cameraKitStub, withPreview = true)
            configureLenses {
                // When CameraKit is configured to manage its own views by providing a view stub (see above),
                // lenses touch handling might consume all events due to the fact that it needs to perform gesture
                // detection internally. If application needs to handle gestures on top of it then LensesComponent
                // provides a way to dispatch all touch events unhandled by active lens back.
//                dispatchTouchEventsTo(previewGestureHandler)
                configureCarousel {

                    // Initially, we configure the carousel with group IDs that are set or persisted with CameraLayout
                    // view itself. However, after lensesCarouselConfiguration is applied below, we need to read the
                    // group IDs again in order to update the internal set with user provided values that may possibly
                    // change after view state restoration.
                    observedGroupIds = lensesCarouselObservedGroupIds
                    heightDimenRes = lensesCarouselHeightDimenRes
                    paddingTopDimenRes = lensesCarouselPaddingTopDimenRes.takeIf { it > -1 }
                    paddingBottomDimenRes = lensesCarouselPaddingBottomDimenRes.takeIf { it > -1 }
                    marginBottomDimenRes = lensesCarouselMarginBottomDimenRes
                    closeButtonMarginBottomDimenRes = lensesCarouselCloseButtonMarginBottomDimenRes
                    activateOnStart = lensesCarouselActivated
                    activateOnTap = true
                    deactivateOnClose = true
                    lensesCarouselConfiguration(this)
                    lensesCarouselObservedGroupIds = observedGroupIds
                }

                configureHints {
                    enabled = true
                }

                configureCache {
                    lensContentMaxSize = DEFAULT_LENSES_CONTENT_CACHE_SIZE_MAX_BYTES
                }

                lensesConfiguration(this)
            }
            configureSessionInternal(this)
        }.also { session ->
            session.lenses.apply {
                var skipLensesProcessorEvent = true
                processor.observe { event ->
                    // First event emitted by lenses processor is always Idle, we skip to avoid overriding appliedLensId
                    // with null when it is persisted in SavedState.
                    if (skipLensesProcessorEvent) {
                        skipLensesProcessorEvent = false
                    } else {
                        onChooseFacingForLensRunnable.getAndSet(null)?.let {
                            removeCallbacks(it)
                        }
                        event.whenApplied { it ->
                            appliedLensId = it.lens.id
                            val lensFacingFront = when (onChooseFacingForLens(it.lens)) {
                                LensesComponent.Lens.Facing.FRONT -> true
                                LensesComponent.Lens.Facing.BACK -> false
                                else -> null
                            }
                            if (lensFacingFront != null) {
                                val runnable = Runnable {
                                    if (lensFacingFront != cameraFacingFront) {
                                        startPreview(lensFacingFront)
                                    }
                                }.also {
                                    onChooseFacingForLensRunnable.set(it)
                                }
                                post(runnable)
                            }
                        }
                        event.whenIdle {
                            appliedLensId = null
                        }
                    }
                }.closeOnDetach()
                repository.observe(
                    LensesComponent.Repository.QueryCriteria.Available(lensesCarouselObservedGroupIds)
                ) { available ->
                    available.whenHasSome { lenses ->
                        appliedLensId?.let { id ->
                            lenses.find { it.id == id }?.let(processor::apply)
                        }
                    }
                }.closeOnDetach()
                carousel.observe { event ->
                    post {
                        event.whenActivated {
                            lensesCarouselActivated = true
                        }
                        event.whenDeactivated {
                            lensesCarouselActivated = false
                        }
                        event.whenActivatedIdle {
                            captureButton.isEnabled = true
                            captureButton.centerFill = true
                        }
                        event.whenActivatedWithLens {
                            captureButton.centerFill = false
                        }
                    }
                    event.whenActivatedWithLens {
                        lensDownloadStatus.close()
                        lensDownloadStatus = session.lenses.prefetcher.observe(it.lens) { status ->
                            post {
                                captureButton.isEnabled = status == LensesComponent.Prefetcher.Status.LOADED
                            }
                        }
                    }
                }.closeOnDetach()
            }

            captureButton.apply {
                fallbackTouchHandlerViewId = R.id.camerakit_root

                onCaptureRequestListener = object : SnapButtonView.OnCaptureRequestListener {

                    override fun onStart(captureType: SnapButtonView.CaptureType) {
                        isSessionCapturing = true
                    }

                    override fun onEnd(captureType: SnapButtonView.CaptureType) {
                        isSessionCapturing = false

                        if (captureType == SnapButtonView.CaptureType.SNAPSHOT) {
                            if (cameraImageCapturePhoto) {
                                (activeImageProcessorSource as? AllowsPhotoCapture)?.takePhoto(onImageTaken)
                            } else {
                                (activeImageProcessorSource as? AllowsSnapshotCapture)?.takeSnapshot(onImageTaken)
                            }
                        }
                    }
                }
            }
        }
    }

    protected var activeImageProcessorSource: Source<ImageProcessor> = Source.Noop.get()

    private val imageProcessorSource: Source<ImageProcessor> by lazy {
        // Here we obtain Source of ImageProcessor backed by the CameraX library which simplifies quite a bit
        // of things related to Android camera management. CameraX is one of many options to implement Source,
        // anything that can provide image frames through a SurfaceTexture can be used by CameraKit.
        val cameraXImageProcessorSource = CameraXImageProcessorSource(
            context = context,
            lifecycleOwner = context as LifecycleOwner,
            executorService = serialExecutorService,
            videoOutputDirectory = context.cacheDir
        )

        // Use cameraXImageProcessorSource as an active source by default.
        activeImageProcessorSource = cameraXImageProcessorSource

        if (context.arCoreAvailable) {
            val surfaceTrackingSourceProvider = {
                // ArCoreImageProcessorSource is the only currently supported option to provide surface tracking data
                // or depth data when required by applied lens.
                val arCoreSupportedAndInstalled =
                    when (val availability = context.blockingCheckArCoreAvailability(computationExecutorService)) {
                        ArCoreApk.Availability.SUPPORTED_INSTALLED -> true
                        else -> availability.isSupported && context.tryToInstallArCore()
                    }
                if (arCoreSupportedAndInstalled) {
                    ArCoreImageProcessorSource(
                        context = context,
                        lifecycleOwner = context as LifecycleOwner,
                        executorService = serialExecutorService,
                        videoOutputDirectory = context.cacheDir
                    )
                } else {
                    null
                }
            }
            // This is an implementation of Source<ImageProcessor> that attach ImageProcessor to one of the provided
            // sources according to ImageProcessor requirements to input capabilities.
            ZMSwitchForSurfaceTrackingImageProcessorSource(
                cameraXImageProcessorSource, surfaceTrackingSourceProvider,
                { source ->
                    if (activeImageProcessorSource != source) {
                        this.activeImageProcessorSource = source
                        // Call startPreview on attached Source to let it dispatch frames to ImageProcessor.
                        startPreview()
                    }
                }
            )
        } else {
            cameraXImageProcessorSource
        }
    }

    private val safeRenderAreaProcessorSource: Source<SafeRenderAreaProcessor> by lazy {
        // Provide the area between the camera flip and capture button as the "safe render" area to CameraKit
        // so that they do not overlap any UI elements rendered internally by lenses.
        SafeRenderAreaProcessorSource(this)
    }

    private val serialExecutorService: ExecutorService by lazy {
        Executors.newSingleThreadExecutor().also { executorService ->
            Closeable {
                executorService.shutdown()
            }.closeOnDetach()
        }
    }

    private val computationExecutorService: ScheduledExecutorService by lazy {
        Executors.newScheduledThreadPool(4).also { executorService ->
            Closeable {
                executorService.shutdown()
            }.closeOnDetach()
        }
    }

    private val requiredPermissions: Array<String> = DEFAULT_REQUIRED_PERMISSIONS

    private fun configureSessionInternal(builder: Session.Builder) {
        sessionConfiguration(
            // Wraps the Session.Builder to update the activeImageProcessorSource if custom Source<ImageProcessor> was
            // was provided within sessionConfiguration.
            object : Session.Builder by builder {

                override fun imageProcessorSource(value: Source<ImageProcessor>): Session.Builder {
                    activeImageProcessorSource = value
                    return builder.imageProcessorSource(value)
                }
            }
        )
    }
}

/**
 * Marshals all the values that [ZCameraLayout] considers important to be persisted.
 */
private class SavedState : AbsSavedState {

    var appliedLensId: String? = null
    var lensesCarouselActivated: Boolean = true
    var cameraFacingFront: Boolean = true
    var cameraAspectRatio: AspectRatio = AspectRatio.RATIO_16_9
    var cameraImageCapturePhoto: Boolean = false
    var cameraMirrorHorizontally: Boolean = false
    var cameraCropToRootViewRatio: Boolean = false
    var lensesCarouselObservedGroupIds = arrayOf<String>()
    var behaviorBundles: MutableList<Bundle?> = mutableListOf()

    constructor(superState: Parcelable) : super(superState)

    private constructor(parcel: Parcel, loader: ClassLoader?) : super(parcel, loader) {
        appliedLensId = parcel.readString()
        lensesCarouselActivated = parcel.readInt() != 0
        cameraFacingFront = parcel.readInt() != 0
        val aspectRatioString = parcel.readString()
        cameraAspectRatio = if (aspectRatioString == null) {
            AspectRatio.RATIO_16_9
        } else {
            AspectRatio.valueOf(aspectRatioString)
        }
        cameraImageCapturePhoto = parcel.readInt() != 0
        cameraMirrorHorizontally = parcel.readInt() != 0
        cameraCropToRootViewRatio = parcel.readInt() != 0
        lensesCarouselObservedGroupIds = parcel.createStringArray() ?: emptyArray()
        parcel.readList(behaviorBundles, Bundle::class.java.classLoader)
    }

    override fun writeToParcel(out: Parcel, flags: Int) {
        super.writeToParcel(out, flags)
        out.writeString(appliedLensId)
        out.writeInt(if (lensesCarouselActivated) 1 else 0)
        out.writeInt(if (cameraFacingFront) 1 else 0)
        out.writeString(cameraAspectRatio.name)
        out.writeInt(if (cameraImageCapturePhoto) 1 else 0)
        out.writeInt(if (cameraMirrorHorizontally) 1 else 0)
        out.writeInt(if (cameraCropToRootViewRatio) 1 else 0)
        out.writeStringArray(lensesCarouselObservedGroupIds)
        out.writeList(behaviorBundles)
    }

    companion object {

        @Suppress("unused") // invoked reflectively by Android runtime
        @JvmField
        val CREATOR = object : Parcelable.ClassLoaderCreator<SavedState> {

            override fun createFromParcel(source: Parcel, loader: ClassLoader?): SavedState {
                return SavedState(source, loader)
            }

            override fun createFromParcel(source: Parcel): SavedState {
                return SavedState(source, null)
            }

            override fun newArray(size: Int): Array<SavedState?> {
                return arrayOfNulls(size)
            }
        }
    }
}

/**
 * Simple implementation of a [Source] for a [SafeRenderAreaProcessor] that calculates a safe render area [Rect] that is
 * between the camera flip and capture buttons present in the provided [ZCameraLayout].
 */
private class SafeRenderAreaProcessorSource(
    ZCameraLayout: ZCameraLayout
) : Source<SafeRenderAreaProcessor> {

    private val cameraLayoutReference = WeakReference(ZCameraLayout)

    override fun attach(processor: SafeRenderAreaProcessor): Closeable {
        return processor.connectInput(object : SafeRenderAreaProcessor.Input {
            override fun subscribeTo(onSafeRenderAreaAvailable: Consumer<Rect>): Closeable {
                val cameraLayout = cameraLayoutReference.get()
                if (cameraLayout == null) {
                    return EMPTY_CLOSEABLE
                } else {
                    val activity = cameraLayout.requireActivity()
                    fun updateSafeRenderRegionIfNecessary() {
                        val safeRenderRect = Rect()
                        if (cameraLayout.getGlobalVisibleRect(safeRenderRect)) {
                            val tmpRect = Rect()
                            activity.window.decorView.getWindowVisibleDisplayFrame(tmpRect)
                            val statusBarHeight = tmpRect.top
                            // Make the zone's top to start below the camera flip button where other camera controls
                            // or app's "top bar" might be located.
//                            if (cameraLayout.flipFacingButton.getGlobalVisibleRect(tmpRect)) {
//                                safeRenderRect.top = tmpRect.bottom - statusBarHeight
//                            }
                            // Make the zone's bottom to start above capture button - anything under or below it should
                            // not be considered safe to render to.
                            if (cameraLayout.captureButton.getGlobalVisibleRect(tmpRect)) {
                                safeRenderRect.bottom = tmpRect.top - statusBarHeight
                            }
                            onSafeRenderAreaAvailable.accept(safeRenderRect)
                        }
                    }
                    // The processor might subscribe to the input when views are laid out already so we can attempt
                    // to calculate the safe render area already:
                    updateSafeRenderRegionIfNecessary()
                    // Otherwise we start listening for layout changes to update the safe render rect continuously:
                    val onLayoutChangeListener = View.OnLayoutChangeListener {
                            _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
                        if (left != oldLeft || top != oldTop || right != oldRight || bottom != oldBottom) {
                            updateSafeRenderRegionIfNecessary()
                        }
                    }
                    cameraLayout.addOnLayoutChangeListener(onLayoutChangeListener)
                    return Closeable {
                        cameraLayout.removeOnLayoutChangeListener(onLayoutChangeListener)
                    }
                }
            }
        })
    }
}

/**
 * Error handler which passes errors on the given [handler] thread to an error callback if provided via [use].
 * This helper ensures that references to the user provided lambdas are kept only for the duration of view lifecycle
 * until the returned [Closeable] is closed.
 */
private class MutableErrorHandler(
    private val handler: Handler = Handler(Looper.getMainLooper())
) : Consumer<Throwable> {

    private var callbackReference = AtomicReference<(Throwable) -> Unit>()

    fun use(callback: (Throwable) -> Unit): Closeable {
        callbackReference.set(callback)
        return Closeable {
            if (!callbackReference.compareAndSet(callback, null)) {
                throw IllegalStateException("Expected $callback to be removed via Closeable")
            }
        }
    }

    override fun accept(throwable: Throwable) {
        handler.post {
            callbackReference.get()?.invoke(throwable)
                ?: Log.e(TAG, "Ignoring an unhandled error due to a missing error handler", throwable)
        }
    }
}
