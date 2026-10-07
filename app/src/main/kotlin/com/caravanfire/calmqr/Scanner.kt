/*
 * Calm QR — a QR code scanner for the Mudita Kompakt
 * Copyright 2026 Jacob Moss
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. A copy is in LICENSE beside this file, and at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 *
 * Taken from Calm QR (https://github.com/jacobrmoss/calm-qr, commit b6a693b),
 * app/src/main/java/com/caravanfire/calmqr/ui/screens/ScannerScreen.kt (CameraPreview) and
 * ScannerOverlay.kt (FocusRing, the centred square).
 *
 * Changed by wander wildwood, 2026, for Passwords (aikotoba):
 *  - frames are read by ZXing's QR reader in place of Calm QR's Rust engine, and the code's
 *    place, found by ZXing's detector when it cannot yet be read, is where the camera is asked
 *    to focus, as Calm QR's engine nudges it;
 *  - the camera is let go when the scanner leaves the screen, not only with the activity;
 *  - the preview is drawn in black and white, the square is a plain black and white frame
 *    with no dimming around it, and the "starting" spinner is a line of text;
 *  - no viewfinder, exact-match, torch or pinch-zoom controls; a read gives one haptic tick.
 */
package com.caravanfire.calmqr

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.util.Size
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size as CSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.detector.Detector
import com.mudita.mmd.components.text.TextMMD
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.delay

/**
 * The camera's live picture, read for a QR code frame after frame; there is no shutter. When a
 * code reads, [onRead] is given its text on the main thread and the reading stops. Returning
 * false from it ("not one of ours") starts the reading again a moment later, with that same
 * code left alone from then on, so it is not reported over and over while it stays in view.
 *
 * The caller must already hold the CAMERA permission. [onUnavailable] is called if the camera
 * cannot be opened at all.
 */
@Composable
fun QrScanner(
    starting: String,
    onRead: (String) -> Boolean,
    onUnavailable: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val read by rememberUpdatedState(onRead)
    val unavailable by rememberUpdatedState(onUnavailable)
    var camera by remember { mutableStateOf<Camera?>(null) }
    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    var focusTick by remember { mutableIntStateOf(0) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val paused = remember { AtomicBoolean(false) }
    var resumeTick by remember { mutableIntStateOf(0) }
    // The codes reported and turned down: not reported again.
    val ignored = remember { java.util.Collections.synchronizedSet(HashSet<String>()) }

    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
            // Black and white, as the panel will show it anyway; on any other screen it keeps
            // the picture from flickering colour that the e-ink build of it never has.
            val grey = Paint().apply { colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) }) }
            setLayerType(View.LAYER_TYPE_HARDWARE, grey)
        }
    }
    var streaming by remember { mutableStateOf(false) }
    DisposableEffect(previewView) {
        val watch = Observer<PreviewView.StreamState> { streaming = it == PreviewView.StreamState.STREAMING }
        previewView.previewStreamState.observe(lifecycleOwner, watch)
        onDispose { previewView.previewStreamState.removeObserver(watch) }
    }
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }

    LaunchedEffect(resumeTick) {
        if (resumeTick == 0) return@LaunchedEffect
        delay(RESUME_MS)
        paused.set(false)
    }

    DisposableEffect(Unit) {
        onDispose {
            // The activity outlives this screen, so the camera is let go here rather than left
            // bound to it until the app closes.
            if (cameraProviderFuture.isDone) runCatching { cameraProviderFuture.get().unbindAll() }
            executor.shutdown()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                cameraProviderFuture.addListener({
                    // The screen can be left before the provider future resolves (a fast back
                    // press on first open); binding to a destroyed lifecycle throws.
                    if (lifecycleOwner.lifecycle.currentState == Lifecycle.State.DESTROYED || executor.isShutdown) {
                        return@addListener
                    }
                    val cameraProvider = cameraProviderFuture.get()

                    // Enough pixels to read a dense code at arm's length, few enough that the
                    // phone reads several frames a second.
                    val resolutionSelector = ResolutionSelector.Builder()
                        .setResolutionStrategy(ResolutionStrategy(Size(1280, 960), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        .build()

                    val preview = Preview.Builder()
                        .setResolutionSelector(resolutionSelector)
                        .build().also { p -> p.setSurfaceProvider(previewView.surfaceProvider) }

                    val imageAnalysis = ImageAnalysis.Builder()
                        .setResolutionSelector(resolutionSelector)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()

                    val reader = Reader()
                    var lastNudge = 0L
                    imageAnalysis.setAnalyzer(executor) { imageProxy ->
                        try {
                            if (paused.get()) return@setAnalyzer
                            when (val r = reader.read(imageProxy)) {
                                is Reader.Result.Text -> {
                                    if (r.text in ignored) return@setAnalyzer
                                    paused.set(true)
                                    previewView.post {
                                        previewView.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                                        if (!read(r.text)) {
                                            ignored.add(r.text)
                                            resumeTick++
                                        }
                                    }
                                }
                                is Reader.Result.Seen -> {
                                    // A code is there but will not read yet: most often it is
                                    // out of focus, so focus on it, now and then.
                                    val now = System.currentTimeMillis()
                                    val cam = camera
                                    if (cam != null && now - lastNudge > NUDGE_MS) {
                                        lastNudge = now
                                        val point = SurfaceOrientedMeteringPointFactory(r.width, r.height, imageAnalysis).createPoint(r.x, r.y)
                                        cam.cameraControl.startFocusAndMetering(
                                            FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                                                .setAutoCancelDuration(3, java.util.concurrent.TimeUnit.SECONDS)
                                                .build(),
                                        )
                                    }
                                }
                                Reader.Result.Nothing -> Unit
                            }
                        } finally {
                            imageProxy.close()
                        }
                    }

                    try {
                        cameraProvider.unbindAll()
                        camera = cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalysis)
                    } catch (e: Exception) {
                        unavailable()
                    }
                }, ContextCompat.getMainExecutor(ctx))
                previewView
            },
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(camera) {
                    detectTapGestures { offset ->
                        val cam = camera ?: return@detectTapGestures
                        val point = previewView.meteringPointFactory.createPoint(offset.x, offset.y)
                        cam.cameraControl.cancelFocusAndMetering()
                        cam.cameraControl.startFocusAndMetering(FocusMeteringAction.Builder(point).build())
                        focusPoint = offset
                        focusTick++
                    }
                },
        )

        Square()
        FocusRing(position = focusPoint, tick = focusTick, onFinished = { focusPoint = null })

        if (!streaming) {
            Box(
                modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center,
            ) {
                TextMMD(text = starting, modifier = Modifier.padding(16.dp))
            }
        }
    }
}

/** ZXing over the brightness plane of a camera frame: the code's text, or where a code is. */
private class Reader {
    sealed class Result {
        class Text(val text: String) : Result()
        /** A code found at ([x], [y]) in a frame [width] by [height], not readable yet. */
        class Seen(val x: Float, val y: Float, val width: Float, val height: Float) : Result()
        object Nothing : Result()
    }

    private val reader = QRCodeReader()
    private val hints = mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE))
    private var bytes = ByteArray(0)

    fun read(image: ImageProxy): Result {
        val plane = image.planes[0]
        val buffer = plane.buffer.duplicate().apply { rewind() }
        val stride = plane.rowStride
        val w = image.width
        val h = image.height
        val need = stride * h
        if (bytes.size != need) bytes = ByteArray(need)
        buffer.get(bytes, 0, minOf(buffer.remaining(), need))
        val bitmap = BinaryBitmap(HybridBinarizer(PlanarYUVLuminanceSource(bytes, stride, h, 0, 0, w, h, false)))
        return try {
            Result.Text(reader.decode(bitmap, hints).text)
        } catch (e: Exception) {
            try {
                val points = Detector(bitmap.blackMatrix).detect(hints).points
                if (points.isEmpty()) Result.Nothing
                else Result.Seen(points.map { it.x }.average().toFloat(), points.map { it.y }.average().toFloat(), w.toFloat(), h.toFloat())
            } catch (e: Exception) {
                Result.Nothing
            }
        } finally {
            reader.reset()
        }
    }
}

/**
 * Where to hold the code: a centred square, drawn black with a white line inside so it shows
 * on any picture. Only a guide; a code anywhere in the frame is read.
 */
@Composable
private fun Square() {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val rect = square(size)
        drawRect(Color.Black, rect.topLeft, rect.size, style = Stroke(width = 6.dp.toPx()))
        drawRect(Color.White, rect.topLeft, rect.size, style = Stroke(width = 2.dp.toPx()))
    }
}

private fun square(canvas: CSize, fraction: Float = 0.7f): Rect {
    val side = minOf(canvas.width, canvas.height) * fraction
    val left = (canvas.width - side) / 2f
    val top = (canvas.height - side) / 2f
    return Rect(left, top, left + side, top + side)
}

/**
 * A focus ring where the picture was tapped, held briefly, then cleared: solid black and white
 * at full strength with no fade, which the panel's fast mode draws crisply.
 */
@Composable
private fun FocusRing(position: Offset?, tick: Int, onFinished: () -> Unit) {
    if (position == null) return
    LaunchedEffect(tick) {
        delay(450)
        onFinished()
    }
    Canvas(modifier = Modifier.fillMaxSize()) {
        val r = 30.dp.toPx()
        drawCircle(color = Color.Black, radius = r, center = position, style = Stroke(width = 7.dp.toPx()))
        drawCircle(color = Color.White, radius = r, center = position, style = Stroke(width = 3.dp.toPx()))
    }
}

private const val RESUME_MS = 1500L
private const val NUDGE_MS = 2000L
