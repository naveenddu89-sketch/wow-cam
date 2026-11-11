package com.wowcam.app.effect

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import android.view.Surface
import androidx.camera.core.ProcessingException
import androidx.camera.core.SurfaceOutput
import androidx.camera.core.SurfaceProcessor
import androidx.camera.core.SurfaceRequest
import androidx.core.util.Consumer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * GPU color-matrix processor for CameraX.
 *
 * The camera writes OES frames into a [SurfaceTexture]; every frame is drawn once per
 * output [SurfaceOutput] (Preview and VideoCapture) through a tiny fragment shader that
 * applies [transform]. The same pass therefore feeds the viewfinder and the H.264
 * encoder, which is what makes the filters show up in the recorded MP4.
 *
 * All EGL/GL work happens on one dedicated [HandlerThread]. CameraX callbacks are
 * dispatched through [executor], and the SurfaceTexture frame callback uses the same
 * handler, so no GL context is ever touched from two threads.
 */
class ColorMatrixSurfaceProcessor(
    private val onError: (Throwable) -> Unit
) : SurfaceProcessor, SurfaceTexture.OnFrameAvailableListener {

    private val glThread = HandlerThread(
        "WowCamColorGL",
        Process.THREAD_PRIORITY_DISPLAY
    ).apply { start() }
    private val glHandler = Handler(glThread.looper)

    /** Executor handed to CameraX so every effect callback lands on the GL thread. */
    val executor: Executor = Executor { command -> runOnGlThread { command.run() } }

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null
    private var dummyEglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var program = 0
    private var aPosition = -1
    private var aTexCoord = -1
    private var uTexture = -1
    private var uTexMatrix = -1
    private var uColorMatrix = -1
    private var uColorOffset = -1

    private var input: Input? = null
    private val outputs = ArrayList<Output>()

    private val sourceMatrix = FloatArray(16)
    private val outputMatrix = FloatArray(16)
    private val viewport = IntArray(2)
    private val queryWidth = IntArray(1)
    private val queryHeight = IntArray(1)

    @Volatile
    private var transform: ColorTransform = ColorFilters.NORMAL

    @Volatile
    private var released = false

    private val quad: FloatBuffer = directFloats(
        -1f, -1f,
        1f, -1f,
        -1f, 1f,
        1f, 1f
    )

    private val texCoords: FloatBuffer = directFloats(
        0f, 0f,
        1f, 0f,
        0f, 1f,
        1f, 1f
    )

    /** Swaps the live cinematic look. Safe to call from the main thread at any time. */
    fun setTransform(value: ColorTransform) {
        transform = value
    }

    // ------------------------------------------------------------------------------------
    // CameraX SurfaceProcessor
    // ------------------------------------------------------------------------------------

    override fun onInputSurface(request: SurfaceRequest) {
        if (released) {
            request.willNotProvideSurface()
            return
        }
        try {
            ensureEgl()
            ensureProgram()

            // Detach the previous listener; its Surface is released by its own
            // provideSurface result callback once CameraX is done with it.
            input?.surfaceTexture?.setOnFrameAvailableListener(null)

            val textureId = createExternalTexture()
            val surfaceTexture = SurfaceTexture(textureId)
            val resolution = request.resolution
            surfaceTexture.setDefaultBufferSize(resolution.width, resolution.height)
            val surface = Surface(surfaceTexture)
            val newInput = Input(textureId, surfaceTexture, surface)
            input = newInput
            surfaceTexture.setOnFrameAvailableListener(this, glHandler)

            request.provideSurface(
                surface,
                executor,
                Consumer<SurfaceRequest.Result> { releaseInput(newInput) }
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Could not create effect input surface", t)
            onError(t)
            throw ProcessingException()
        }
    }

    override fun onOutputSurface(surfaceOutput: SurfaceOutput) {
        if (released) {
            surfaceOutput.close()
            return
        }
        try {
            ensureEgl()
            val scene = surfaceOutput.getSurface(executor) { event ->
                if (event.eventCode == SurfaceOutput.Event.EVENT_REQUEST_CLOSE) {
                    releaseOutput(surfaceOutput)
                }
            }
            val eglSurface = EGL14.eglCreateWindowSurface(
                eglDisplay,
                eglConfig,
                scene,
                intArrayOf(EGL14.EGL_NONE),
                0
            )
            if (eglSurface == null || eglSurface == EGL14.EGL_NO_SURFACE) {
                throw IllegalStateException(
                    "eglCreateWindowSurface failed: 0x" +
                        Integer.toHexString(EGL14.eglGetError())
                )
            }
            val size = surfaceOutput.size
            outputs += Output(surfaceOutput, scene, eglSurface, size.width, size.height)
        } catch (t: Throwable) {
            Log.e(TAG, "Could not create effect output surface", t)
            runCatching { surfaceOutput.close() }
            onError(t)
            throw ProcessingException()
        }
    }

    override fun onFrameAvailable(surfaceTexture: SurfaceTexture) {
        val current = input ?: return
        if (current.surfaceTexture !== surfaceTexture || released) return

        try {
            // A camera output surface must be current before updateTexImage() so the
            // OES texture is updated in this processor's EGL context.
            val first = outputs.firstOrNull { !it.closed } ?: return
            if (!makeCurrent(first)) return

            surfaceTexture.updateTexImage()
            surfaceTexture.getTransformMatrix(sourceMatrix)

            val liveTransform = transform
            val textureId = current.textureId
            for (index in outputs.indices) {
                val output = outputs[index]
                if (output.closed) continue
                draw(output, textureId, surfaceTexture.timestamp, liveTransform)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Frame render failed", t)
        }
    }

    // ------------------------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------------------------

    private fun draw(
        output: Output,
        textureId: Int,
        timestampNs: Long,
        liveTransform: ColorTransform
    ) {
        if (!makeCurrent(output)) return

        // CameraX computes the per-use-case crop/rotation/mirroring once per frame.
        output.surfaceOutput.updateTransformMatrix(outputMatrix, sourceMatrix)

        surfaceSize(output.eglSurface, output.width, output.height, viewport)
        GLES20.glViewport(0, 0, viewport[0], viewport[1])
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(uTexture, 0)
        GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, outputMatrix, 0)
        GLES20.glUniformMatrix3fv(uColorMatrix, 1, false, liveTransform.matrix, 0)
        GLES20.glUniform3fv(uColorOffset, 1, liveTransform.offset, 0)

        GLES20.glEnableVertexAttribArray(aPosition)
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glEnableVertexAttribArray(aTexCoord)
        GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 0, texCoords)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPosition)
        GLES20.glDisableVertexAttribArray(aTexCoord)

        // The encoder relies on this timestamp; without it the MP4 runs at the wrong speed.
        EGLExt.eglPresentationTimeANDROID(eglDisplay, output.eglSurface, timestampNs)
        if (!EGL14.eglSwapBuffers(eglDisplay, output.eglSurface)) {
            Log.w(TAG, "eglSwapBuffers failed: 0x" + Integer.toHexString(EGL14.eglGetError()))
        }
    }

    // ------------------------------------------------------------------------------------
    // EGL / program setup
    // ------------------------------------------------------------------------------------

    private fun ensureEgl() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) return

        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "eglGetDisplay failed" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) {
            "eglInitialize failed: 0x" + Integer.toHexString(EGL14.eglGetError())
        }

        // Prefer one config that supports both window and pbuffer surfaces so the
        // same config is used for the context, its dummy pbuffer and the outputs.
        // Some drivers only expose window-only configs, hence the fallbacks.
        val config = chooseConfig(
            display,
            EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            recordable = true
        )
            ?: chooseConfig(display, EGL14.EGL_WINDOW_BIT, recordable = true)
            ?: chooseConfig(
                display,
                EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
                recordable = false
            )
            ?: chooseConfig(display, EGL14.EGL_WINDOW_BIT, recordable = false)
            ?: throw IllegalStateException(
                "No usable EGL config: 0x" + Integer.toHexString(EGL14.eglGetError())
            )

        val context = EGL14.eglCreateContext(
            display,
            config,
            EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
            0
        )
        check(context != EGL14.EGL_NO_CONTEXT) {
            "eglCreateContext failed: 0x" + Integer.toHexString(EGL14.eglGetError())
        }

        eglDisplay = display
        eglConfig = config
        eglContext = context

        // Shader compilation and glGenTextures need a current context before any
        // camera output surface exists. A 1x1 pbuffer provides that context. If the
        // chosen config has no pbuffer support, fall back to a surfaceless context.
        val pbuffer = EGL14.eglCreatePbufferSurface(
            display,
            config,
            intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE),
            0
        )
        if (pbuffer != null && pbuffer != EGL14.EGL_NO_SURFACE) {
            dummyEglSurface = pbuffer
            if (!EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)) {
                Log.w(TAG, "Could not make pbuffer current: 0x" +
                    Integer.toHexString(EGL14.eglGetError()))
                EGL14.eglDestroySurface(display, pbuffer)
                dummyEglSurface = EGL14.EGL_NO_SURFACE
            }
        }
        if (dummyEglSurface == EGL14.EGL_NO_SURFACE) {
            if (!EGL14.eglMakeCurrent(
                    display,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_SURFACE,
                    context
                )
            ) {
                // Some older drivers require a surface; the first output surface will
                // become current as soon as CameraX delivers it.
                Log.w(TAG, "Surfaceless EGL context unavailable")
            }
        }

        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    private fun chooseConfig(
        display: EGLDisplay,
        surfaceType: Int,
        recordable: Boolean
    ): EGLConfig? {
        val attribs = ArrayList<Int>(16).apply {
            add(EGL14.EGL_RENDERABLE_TYPE)
            add(EGL14.EGL_OPENGL_ES2_BIT)
            add(EGL14.EGL_SURFACE_TYPE)
            add(surfaceType)
            add(EGL14.EGL_RED_SIZE)
            add(8)
            add(EGL14.EGL_GREEN_SIZE)
            add(8)
            add(EGL14.EGL_BLUE_SIZE)
            add(8)
            add(EGL14.EGL_ALPHA_SIZE)
            add(8)
            if (recordable) {
                add(EGL_RECORDABLE_ANDROID)
                add(1)
            }
            add(EGL14.EGL_NONE)
        }
        val attribList = attribs.toIntArray()
        val configs = arrayOfNulls<EGLConfig>(1)
        val configCount = IntArray(1)
        val found = EGL14.eglChooseConfig(
            display,
            attribList,
            0,
            configs,
            0,
            1,
            configCount,
            0
        )
        return if (found && configCount[0] > 0) configs[0] else null
    }

    private fun ensureProgram() {
        if (program != 0) return

        val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        val newProgram = GLES20.glCreateProgram()
        check(newProgram != 0) { "glCreateProgram failed" }
        GLES20.glAttachShader(newProgram, vertexShader)
        GLES20.glAttachShader(newProgram, fragmentShader)
        GLES20.glLinkProgram(newProgram)

        val status = IntArray(1)
        GLES20.glGetProgramiv(newProgram, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(newProgram)
            GLES20.glDeleteProgram(newProgram)
            throw IllegalStateException("Program link failed: $log")
        }
        GLES20.glDeleteShader(vertexShader)
        GLES20.glDeleteShader(fragmentShader)

        program = newProgram
        aPosition = GLES20.glGetAttribLocation(newProgram, "aPosition")
        aTexCoord = GLES20.glGetAttribLocation(newProgram, "aTexCoord")
        uTexture = GLES20.glGetUniformLocation(newProgram, "uTexture")
        uTexMatrix = GLES20.glGetUniformLocation(newProgram, "uTexMatrix")
        uColorMatrix = GLES20.glGetUniformLocation(newProgram, "uColorMatrix")
        uColorOffset = GLES20.glGetUniformLocation(newProgram, "uColorOffset")
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        check(shader != 0) { "glCreateShader failed" }
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw IllegalStateException("Shader compile failed: $log")
        }
        return shader
    }

    private fun createExternalTexture(): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        check(ids[0] != 0) { "glGenTextures failed" }
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, ids[0])
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MIN_FILTER,
            GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MAG_FILTER,
            GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_S,
            GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_T,
            GLES20.GL_CLAMP_TO_EDGE
        )
        return ids[0]
    }

    private fun surfaceSize(
        eglSurface: EGLSurface,
        fallbackWidth: Int,
        fallbackHeight: Int,
        out: IntArray
    ) {
        val okWidth = EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_WIDTH, queryWidth, 0)
        val okHeight = EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_HEIGHT, queryHeight, 0)
        out[0] = if (okWidth && queryWidth[0] > 0) queryWidth[0] else fallbackWidth
        out[1] = if (okHeight && queryHeight[0] > 0) queryHeight[0] else fallbackHeight
    }

    private fun makeCurrent(output: Output): Boolean {
        if (EGL14.eglMakeCurrent(
                eglDisplay,
                output.eglSurface,
                output.eglSurface,
                eglContext
            )
        ) {
            return true
        }
        Log.w(TAG, "eglMakeCurrent failed: 0x" + Integer.toHexString(EGL14.eglGetError()))
        return false
    }

    private fun makeContextCurrent() {
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) return
        if (dummyEglSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(eglDisplay, dummyEglSurface, dummyEglSurface, eglContext)
        } else {
            EGL14.eglMakeCurrent(
                eglDisplay,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                eglContext
            )
        }
    }

    // ------------------------------------------------------------------------------------
    // Teardown
    // ------------------------------------------------------------------------------------

    private fun releaseInput(target: Input) {
        if (!target.released.compareAndSet(false, true)) return
        if (input === target) input = null
        target.surfaceTexture.setOnFrameAvailableListener(null)
        target.surfaceTexture.release()
        target.surface.release()
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            makeContextCurrent()
            GLES20.glDeleteTextures(1, intArrayOf(target.textureId), 0)
        }
    }

    private fun releaseOutput(surfaceOutput: SurfaceOutput) {
        val index = outputs.indexOfFirst { it.surfaceOutput === surfaceOutput }
        if (index < 0) return
        val output = outputs.removeAt(index)
        output.closed = true
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            makeContextCurrent()
            EGL14.eglDestroySurface(eglDisplay, output.eglSurface)
        }
        surfaceOutput.close()
    }

    /**
     * Releases the processor. Call this after CameraX has unbound the use cases.
     * Safe to call from any thread.
     */
    fun release() {
        if (released) return
        released = true
        runOnGlThread {
            makeContextCurrent()
            input?.let { releaseInput(it) }

            if (program != 0) {
                GLES20.glDeleteProgram(program)
                program = 0
            }

            for (output in ArrayList(outputs)) {
                releaseOutput(output.surfaceOutput)
            }
            outputs.clear()

            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(
                    eglDisplay,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT
                )
                if (dummyEglSurface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(eglDisplay, dummyEglSurface)
                    dummyEglSurface = EGL14.EGL_NO_SURFACE
                }
                if (eglContext != EGL14.EGL_NO_CONTEXT) {
                    EGL14.eglDestroyContext(eglDisplay, eglContext)
                }
                EGL14.eglReleaseThread()
                EGL14.eglTerminate(eglDisplay)
            }
            eglDisplay = EGL14.EGL_NO_DISPLAY
            eglContext = EGL14.EGL_NO_CONTEXT
            eglConfig = null
            glThread.quitSafely()
        }
    }

    private fun runOnGlThread(command: () -> Unit) {
        if (Thread.currentThread() === glThread) {
            command()
        } else {
            glHandler.post { command() }
        }
    }

    private class Input(
        val textureId: Int,
        val surfaceTexture: SurfaceTexture,
        val surface: Surface
    ) {
        val released = AtomicBoolean(false)
    }

    private class Output(
        val surfaceOutput: SurfaceOutput,
        val surface: Surface,
        val eglSurface: EGLSurface,
        val width: Int,
        val height: Int
    ) {
        var closed = false
    }

    private companion object {
        const val TAG = "WowCamColorEffect"

        // EGLExt.EGL_RECORDABLE_ANDROID is inlined by the compiler and only exposed
        // from API 26, while the value itself works on every EGL implementation.
        const val EGL_RECORDABLE_ANDROID = 0x3142

        val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            uniform mat4 uTexMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uTexMatrix * aTexCoord).xy;
            }
        """.trimIndent()

        val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif
            uniform samplerExternalOES uTexture;
            uniform mat3 uColorMatrix;
            uniform vec3 uColorOffset;
            varying vec2 vTexCoord;
            void main() {
                vec4 source = texture2D(uTexture, vTexCoord);
                vec3 rgb = clamp(uColorMatrix * source.rgb + uColorOffset, 0.0, 1.0);
                gl_FragColor = vec4(rgb, source.a);
            }
        """.trimIndent()

        fun directFloats(vararg values: Float): FloatBuffer {
            val buffer = ByteBuffer
                .allocateDirect(values.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
            buffer.put(values)
            buffer.position(0)
            return buffer
        }
    }
}
