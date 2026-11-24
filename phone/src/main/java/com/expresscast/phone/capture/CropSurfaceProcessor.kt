package com.expresscast.phone.capture

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * GPU-accelerated [SurfaceTexture] -> [EGLSurface] processor that center-crops
 * a [VirtualDisplay] capture surface onto the H.264 encoder's input [Surface].
 */
class CropSurfaceProcessor(
    private val captureWidth: Int,
    private val captureHeight: Int,
    private val encodeWidth: Int,
    private val encodeHeight: Int,
    private val encoderInputSurface: Surface
) {
    private val glThread = HandlerThread("ExpressCast-GlCrop").apply { start() }
    private val glHandler = Handler(glThread.looper)

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var textureId: Int = 0
    private var programId: Int = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var captureSurface: Surface? = null

    private val stMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)

    @Volatile
    private var released = false

    fun startAndGetInputSurface(): Surface {
        val latch = CountDownLatch(1)
        var initError: Throwable? = null
        glHandler.post {
            try {
                initGl()
            } catch (t: Throwable) {
                initError = t
            } finally {
                latch.countDown()
            }
        }
        latch.await(3, TimeUnit.SECONDS)
        initError?.let { throw IllegalStateException("Failed to initialize GL cropper", it) }
        return requireNotNull(captureSurface) { "Capture surface not initialized" }
    }

    fun release() {
        if (released) return
        released = true
        val latch = CountDownLatch(1)
        glHandler.post {
            try {
                captureSurface?.release()
                captureSurface = null
                surfaceTexture?.release()
                surfaceTexture = null
                if (programId != 0) {
                    GLES20.glDeleteProgram(programId)
                    programId = 0
                }
                if (textureId != 0) {
                    GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
                    textureId = 0
                }
                if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
                    if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
                    EGL14.eglTerminate(eglDisplay)
                }
                eglDisplay = EGL14.EGL_NO_DISPLAY
                eglContext = EGL14.EGL_NO_CONTEXT
                eglSurface = EGL14.EGL_NO_SURFACE
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing GL cropper: ${e.message}")
            } finally {
                latch.countDown()
            }
        }
        latch.await(1, TimeUnit.SECONDS)
        glThread.quitSafely()
    }

    private fun initGl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        EGL14.eglInitialize(eglDisplay, version, 0, version, 1)

        val attribList = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, 1, numConfigs, 0)
        val eglConfig = configs[0] ?: throw IllegalStateException("No matching EGLConfig")

        eglContext = EGL14.eglCreateContext(
            eglDisplay,
            eglConfig,
            EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
            0
        )
        eglSurface = EGL14.eglCreateWindowSurface(
            eglDisplay,
            eglConfig,
            encoderInputSurface,
            intArrayOf(EGL14.EGL_NONE),
            0
        )
        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)

        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        programId = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        computeCenterCropMatrix()

        val st = SurfaceTexture(textureId).apply {
            setDefaultBufferSize(captureWidth, captureHeight)
            setOnFrameAvailableListener({ onFrameAvailable(it) }, glHandler)
        }
        surfaceTexture = st
        captureSurface = Surface(st)
    }

    private fun computeCenterCropMatrix() {
        Matrix.setIdentityM(mvpMatrix, 0)
        val srcAspect = captureWidth.toFloat() / captureHeight.coerceAtLeast(1)
        val dstAspect = encodeWidth.toFloat() / encodeHeight.coerceAtLeast(1)
        if (srcAspect > dstAspect) {
            // Source is wider than target -> scale X up to crop left/right
            Matrix.scaleM(mvpMatrix, 0, srcAspect / dstAspect, 1f, 1f)
        } else if (srcAspect < dstAspect) {
            // Source is taller than target -> scale Y up to crop top/bottom
            Matrix.scaleM(mvpMatrix, 0, 1f, dstAspect / srcAspect, 1f)
        }
    }

    private fun onFrameAvailable(st: SurfaceTexture) {
        if (released) return
        try {
            st.updateTexImage()
            st.getTransformMatrix(stMatrix)

            GLES20.glViewport(0, 0, encodeWidth, encodeHeight)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            GLES20.glUseProgram(programId)
            val aPos = GLES20.glGetAttribLocation(programId, "aPosition")
            val aTex = GLES20.glGetAttribLocation(programId, "aTexCoord")
            val uMvp = GLES20.glGetUniformLocation(programId, "uMvpMatrix")
            val uTex = GLES20.glGetUniformLocation(programId, "uTexMatrix")

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)

            GLES20.glUniformMatrix4fv(uMvp, 1, false, mvpMatrix, 0)
            GLES20.glUniformMatrix4fv(uTex, 1, false, stMatrix, 0)

            QUAD_VERTICES.position(0)
            GLES20.glEnableVertexAttribArray(aPos)
            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, QUAD_VERTICES)

            QUAD_VERTICES.position(2)
            GLES20.glEnableVertexAttribArray(aTex)
            GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 16, QUAD_VERTICES)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, st.timestamp)
            EGL14.eglSwapBuffers(eglDisplay, eglSurface)
        } catch (e: Exception) {
            if (!released) {
                Log.w(TAG, "GL crop frame error: ${e.message}")
            }
        }
    }

    private fun buildProgram(vs: String, fs: String): Int {
        val vShader = compileShader(GLES20.GL_VERTEX_SHADER, vs)
        val fShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fs)
        return GLES20.glCreateProgram().also { prog ->
            GLES20.glAttachShader(prog, vShader)
            GLES20.glAttachShader(prog, fShader)
            GLES20.glLinkProgram(prog)
            GLES20.glDeleteShader(vShader)
            GLES20.glDeleteShader(fShader)
        }
    }

    private fun compileShader(type: Int, source: String): Int {
        return GLES20.glCreateShader(type).also { shader ->
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
        }
    }

    private companion object {
        const val TAG = "ExpressCast.GlCrop"
        const val EGL_RECORDABLE_ANDROID = 0x3142

        val QUAD_VERTICES: FloatBuffer = ByteBuffer
            .allocateDirect(16 * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(
                floatArrayOf(
                    -1f, -1f, 0f, 0f,
                     1f, -1f, 1f, 0f,
                    -1f,  1f, 0f, 1f,
                     1f,  1f, 1f, 1f
                )
            ).apply { position(0) }

        const val VERTEX_SHADER = """
            uniform mat4 uMvpMatrix;
            uniform mat4 uTexMatrix;
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = uMvpMatrix * aPosition;
                vTexCoord = (uTexMatrix * aTexCoord).xy;
            }
        """

        const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, vTexCoord);
            }
        """
    }
}
