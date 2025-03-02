package com.zmckitlibrary.widgets

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewStub
import androidx.annotation.MainThread
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.lifecycle.LifecycleOwner
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager.HORIZONTAL
import androidx.recyclerview.widget.LinearSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.snap.camerakit.ImageProcessor
import com.snap.camerakit.SafeRenderAreaProcessor
import com.snap.camerakit.Session
import com.snap.camerakit.Source
import com.snap.camerakit.common.Consumer
import com.snap.camerakit.invoke
import com.snap.camerakit.lenses.LensesComponent
import com.snap.camerakit.support.camera.AllowsCameraPreview
import com.snap.camerakit.support.camera.AllowsPhotoCapture
import com.snap.camerakit.support.camera.AspectRatio
import com.snap.camerakit.support.camera.Crop
import com.snap.camerakit.support.camerax.CameraXImageProcessorSource
import com.snap.camerakit.support.permissions.HeadlessFragmentPermissionRequester
import com.snap.camerakit.support.widget.SnapButtonView
import com.snap.camerakit.supported
import com.zmckitlibrary.R
import java.io.Closeable
import java.lang.ref.WeakReference
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

private val DEFAULT_REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)

open class SnapCameraLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ConstraintLayout(context, attrs, defStyleAttr) {

    private lateinit var lensRecyclerView: RecyclerView

    private val cameraKitStub: ViewStub
    private var isSessionCapturing: Boolean = false
    private var isCameraFacingFront: Boolean = false

    private var isAttached = false
    private val closeOnDetach = mutableListOf<Closeable>()
    private var cameraKitSession: Session? = null

    private var onSessionAvailable: (Session) -> Unit = {}
    private var onImageTaken: (Bitmap) -> Unit = {}
    private var onAppliedLens: (String) -> Unit = {}

    private var sessionConfiguration: Session.Builder.() -> Unit = {}

    private val errorHandler = MutableErrorHandler()

    sealed class Failure(override val message: String) : RuntimeException(message) {
        class OnError(message: String) : Failure(message)
    }

    init {
        LayoutInflater.from(context).inflate(R.layout.camera_layout,
            this, true)
        cameraKitStub = findViewById(R.id.camerakit_stub)
    }

    val captureButton: SnapButtonView = findViewById(R.id.button_capture)

    @MainThread
    @JvmOverloads
    fun startPreview(
        facingFront: Boolean = isCameraFacingFront,
        callback: (succeeded: Boolean) -> Unit = {}
    ) {
        isCameraFacingFront = facingFront
        val options = emptySet<ImageProcessor.Input.Option>()
        val cropOption = Crop.None
        val aspectRatio = AspectRatio.RATIO_16_9

        val configuration = AllowsCameraPreview.Configuration.Default(
            facingFront,
            aspectRatio,
            cropOption
        )

        (activeImageProcessorSource as? AllowsCameraPreview)?.startPreview(
            configuration, options, callback
        )
    }

    fun onImageTaken(callback: (Bitmap) -> Unit) {
        onImageTaken = callback
    }

    fun onAppliedLens(callback: (String) -> Unit) {
        onAppliedLens = callback
    }

    fun onSessionAvailable(callback: (Session) -> Unit) {
        onSessionAvailable = callback
    }

    fun onError(callback: (Throwable) -> Unit) {
        post {
            errorHandler.use(callback).closeOnDetach()
        }
    }

    fun configureSession(withConfiguration: Session.Builder.() -> Unit) {
        sessionConfiguration = withConfiguration
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isAttached = true
        HeadlessFragmentPermissionRequester(
            requireActivity(),
            requiredPermissions.toSet()
        ) { results ->
            if (requiredPermissions.all { results[it] == true }) {
                post {
                    if (isAttached) {
                        if (!onReadyForNewSession()) {
                            errorHandler.accept(Failure.OnError("Device not supported"))
                        }
                    }
                }
            } else {
                errorHandler.accept(Failure.OnError("Permissions missing"))
            }
        }.closeOnDetach()
    }

    override fun onDetachedFromWindow() {
        isAttached = false
        (activeImageProcessorSource as? AllowsCameraPreview)?.stopPreview()
        closeOnDetach.forEach { it.close() }
        closeOnDetach.clear()
        cameraKitSession?.close()
        cameraKitSession = null
        super.onDetachedFromWindow()
    }

    private fun Closeable.closeOnDetach() {
        if (isAttached) closeOnDetach.add(this) else close()
    }

    private fun onReadyForNewSession(): Boolean {
        if (!supported(context)) return false
        cameraKitSession = newSession()
        cameraKitSession?.let {
            onSessionAvailable(it)
        }
        return true
    }

    private fun newSession(): Session = Session(context) {
        imageProcessorSource(imageProcessorSource)
        safeRenderAreaProcessorSource(safeRenderAreaProcessorSource)
        handleErrorsWith(errorHandler)
        attachTo(cameraKitStub, withPreview = true)
        configureSessionInternal(this)
    }.apply {
        setupCaptureButton()
    }

    fun setupCarousel(result: List<LensesComponent.Lens>) {
        lensRecyclerView = findViewById(R.id.lens_carousel)
        // Setup RecyclerView
        val adapter = LensCarouselAdapter(result) { lens ->
            applyLens(lens)
        }
        lensRecyclerView.adapter = adapter
        lensRecyclerView.layoutManager = LinearLayoutManager(requireActivity(),
            HORIZONTAL, false)

        // Attach SnapHelper for smooth snapping
        val snapHelper = LinearSnapHelper()
        snapHelper.attachToRecyclerView(lensRecyclerView)
    }

    fun applyLens(lens: LensesComponent.Lens) {
//        val usingCorrectCamera =
//            cameraFacingFront.xor(lens.facingPreference != LensesComponent.Lens.Facing.FRONT)
//        if (!usingCorrectCamera) {
//            flipCamera()
//        }
        cameraKitSession?.let {
            it.lenses.processor.apply(lens) { success ->
                if (success) {
                    onAppliedLens {
                        lens.id
                    }
                }
            }
        }
    }

//    private fun flipCamera() {
//        Handler(Looper.getMainLooper()).post {
//            (activeImageProcessorSource as? AllowsCameraPreview)?.startPreview(
//                !isCameraFacingFront
//            )
//            isCameraFacingFront = !isCameraFacingFront
//        }
//    }

    private fun setupCaptureButton() {
        captureButton.apply {
            fallbackTouchHandlerViewId = R.id.camerakit_root
            onCaptureRequestListener = object : SnapButtonView.OnCaptureRequestListener {
                override fun onStart(captureType: SnapButtonView.CaptureType) {
                    isSessionCapturing = true
                }

                override fun onEnd(captureType: SnapButtonView.CaptureType) {
                    isSessionCapturing = false
                    if (captureType == SnapButtonView.CaptureType.SNAPSHOT) {
                        (activeImageProcessorSource as? AllowsPhotoCapture)
                            ?.takePhoto(onImageTaken)
                    }
                }
            }
        }
    }

    protected var activeImageProcessorSource: Source<ImageProcessor> = Source.Noop.get()

    private val imageProcessorSource: Source<ImageProcessor> by lazy {
        CameraXImageProcessorSource(
            context = context,
            lifecycleOwner = context as LifecycleOwner,
            executorService = serialExecutorService,
            videoOutputDirectory = context.cacheDir
        ).also { activeImageProcessorSource = it }
    }

    private val safeRenderAreaProcessorSource: Source<SafeRenderAreaProcessor> by lazy {
        SafeRenderAreaProcessorSource(this)
    }

    private val serialExecutorService: ExecutorService by lazy {
        Executors.newSingleThreadExecutor().closeOnDetach()
    }

    private val requiredPermissions: Array<String> = DEFAULT_REQUIRED_PERMISSIONS

    private fun configureSessionInternal(builder: Session.Builder) {
        sessionConfiguration(
            object : Session.Builder by builder {
                override fun imageProcessorSource(value: Source<ImageProcessor>) = builder.also {
                    activeImageProcessorSource = value
                }
            }
        )
    }

    private fun ExecutorService.closeOnDetach(): ExecutorService = apply {
        closeOnDetach.add(Closeable { shutdown() })
    }
}

private class SafeRenderAreaProcessorSource(
    snapCameraLayout: SnapCameraLayout
) : Source<SafeRenderAreaProcessor> {

    private val cameraLayoutReference = WeakReference(snapCameraLayout)

    override fun attach(processor: SafeRenderAreaProcessor): Closeable {
        return processor.connectInput(object : SafeRenderAreaProcessor.Input {
            override fun subscribeTo(onSafeRenderAreaAvailable: Consumer<Rect>): Closeable {
                val cameraLayout = cameraLayoutReference.get()
                if (cameraLayout == null) {
                    return Closeable { }
                } else {
                    val activity = cameraLayout.requireActivity()
                    fun updateSafeRenderRegionIfNecessary() {
                        val safeRenderRect = Rect()
                        if (cameraLayout.getGlobalVisibleRect(safeRenderRect)) {
                            val tmpRect = Rect()
                            activity.window.decorView.getWindowVisibleDisplayFrame(tmpRect)
                            val statusBarHeight = tmpRect.top
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
    private  var tag = "SnapCameraLayout"

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
                ?: Log.e(tag, "Ignoring an unhandled error due to a missing error handler", throwable)
        }
    }
}