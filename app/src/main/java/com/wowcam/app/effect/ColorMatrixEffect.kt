package com.wowcam.app.effect

import androidx.camera.core.CameraEffect
import androidx.core.util.Consumer

/**
 * CameraX effect that feeds both the viewfinder and the video encoder through
 * [ColorMatrixSurfaceProcessor]. Because the processor runs before the encoder,
 * whatever look is selected is baked into the recorded MP4, not just the preview.
 *
 * CameraX 1.3.1 exposes the four-argument [CameraEffect] constructor for
 * `SurfaceProcessor`s; the pipeline delivers one [androidx.camera.core.SurfaceOutput]
 * per target, which is exactly the Preview + VideoCapture pair this app needs.
 */
class ColorMatrixEffect(
    val processor: ColorMatrixSurfaceProcessor,
    errorListener: (Throwable) -> Unit
) : CameraEffect(
    CameraEffect.PREVIEW or CameraEffect.VIDEO_CAPTURE,
    processor.executor,
    processor,
    ErrorConsumer(errorListener)
) {

    fun release() = processor.release()

    private class ErrorConsumer(
        private val listener: (Throwable) -> Unit
    ) : Consumer<Throwable> {
        override fun accept(value: Throwable) = listener(value)
    }
}
