package com.dsoft.screengrab.capture

import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import com.dsoft.screengrab.Log2
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch

/**
 * GL pipeline that takes the full screen (rendered by a VirtualDisplay into [inputSurface]) and draws
 * a (possibly cropped) rectangle onto the encoder's input [outputSurface], at the output size.
 *
 * Full screen = crop covering the whole display. A region = any sub-rectangle. One pipeline does both,
 * so cropping is just different texture coordinates — no extra copy.
 *
 * The orientation mapping matches the classic grafika full-frame mapping for the full-screen case,
 * so a region crop stays upright too. (Still worth verifying on a device.)
 */
class GlPipeline(
    private val outputSurface: Surface,
    private val dispW: Int,
    private val dispH: Int,
    private val crop: Rect,
) {
    lateinit var inputSurface: Surface
        private set
    private lateinit var surfaceTexture: SurfaceTexture

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var texId = 0
    private var aPos = 0
    private var aTex = 0
    private var uStMatrix = 0

    private val stMatrix = FloatArray(16)
    private lateinit var verts: FloatBuffer
    private lateinit var texs: FloatBuffer

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    @Volatile private var frameAvailable = false
    private val lock = Object()
    @Volatile private var released = false

    /** Start the GL thread and block until [inputSurface] is ready. */
    fun start() {
        val latch = CountDownLatch(1)
        val t = HandlerThread("ScreenGrab-GL").also { it.start() }
        thread = t
        handler = Handler(t.looper)
        handler!!.post {
            try {
                initEgl()
                initGl()
                surfaceTexture = SurfaceTexture(texId).apply {
                    setDefaultBufferSize(dispW, dispH)
                    setOnFrameAvailableListener({ synchronized(lock) { frameAvailable = true; lock.notifyAll() } }, handler)
                }
                inputSurface = Surface(surfaceTexture)
            } catch (e: Throwable) {
                Log2.e("GL init failed", e)
            } finally {
                latch.countDown()
            }
        }
        latch.await()
    }

    /** Pull the next frame (if any) and draw it to the encoder surface with the given timestamp. */
    fun drawFrameBlocking(ptsNanos: Long) {
        val h = handler ?: return
        val done = CountDownLatch(1)
        h.post {
            try {
                synchronized(lock) {
                    // wait briefly for a frame; skip if none to avoid stalling
                    var waited = 0
                    while (!frameAvailable && waited < 60 && !released) { lock.wait(5); waited += 5 }
                    if (!frameAvailable) { done.countDown(); return@post }
                    frameAvailable = false
                }
                surfaceTexture.updateTexImage()
                surfaceTexture.getTransformMatrix(stMatrix)
                draw()
                EGLExt_setPresentationTime(ptsNanos)
                EGL14.eglSwapBuffers(eglDisplay, eglSurface)
            } catch (e: Throwable) {
                Log2.e("GL draw failed", e)
            } finally {
                done.countDown()
            }
        }
        done.await()
    }

    fun release() {
        released = true
        val h = handler
        if (h != null) {
            val done = CountDownLatch(1)
            h.post {
                runCatching { if (program != 0) GLES20.glDeleteProgram(program) }
                runCatching { if (texId != 0) GLES20.glDeleteTextures(1, intArrayOf(texId), 0) }
                runCatching { if (::surfaceTexture.isInitialized) surfaceTexture.release() }
                runCatching { if (::inputSurface.isInitialized) inputSurface.release() }
                runCatching {
                    if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                        EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                        if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
                        if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
                        EGL14.eglReleaseThread()
                        EGL14.eglTerminate(eglDisplay)
                    }
                }
                done.countDown()
            }
            done.await()
        }
        thread?.quitSafely()
        thread = null; handler = null
    }

    // ---- setup ----

    private fun initEgl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val ver = IntArray(2)
        EGL14.eglInitialize(eglDisplay, ver, 0, ver, 1)
        val attrib = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1, EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, attrib, 0, configs, 0, 1, num, 0)
        val ctxAttrib = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, ctxAttrib, 0)
        val surfAttrib = intArrayOf(EGL14.EGL_NONE)
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], outputSurface, surfAttrib, 0)
        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
    }

    private fun initGl() {
        program = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        aTex = GLES20.glGetAttribLocation(program, "aTex")
        uStMatrix = GLES20.glGetUniformLocation(program, "uStMatrix")
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        texId = tex[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        // full-screen quad in clip space (BL, BR, TL, TR)
        verts = floatBuf(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))

        // crop → texture coords. Same convention as grafika full-frame so orientation is preserved.
        val sL = crop.left.toFloat() / dispW
        val sR = crop.right.toFloat() / dispW
        val tB = 1f - crop.bottom.toFloat() / dispH
        val tT = 1f - crop.top.toFloat() / dispH
        texs = floatBuf(floatArrayOf(sL, tB, sR, tB, sL, tT, sR, tT))
    }

    private fun draw() {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glUniformMatrix4fv(uStMatrix, 1, false, stMatrix, 0)
        verts.position(0)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, verts)
        texs.position(0)
        GLES20.glEnableVertexAttribArray(aTex)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 0, texs)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aTex)
    }

    private fun EGLExt_setPresentationTime(ns: Long) {
        android.opengl.EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, ns)
    }

    private fun floatBuf(a: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(a); position(0) }

    private fun buildProgram(vs: String, fs: String): Int {
        val v = compile(GLES20.GL_VERTEX_SHADER, vs)
        val f = compile(GLES20.GL_FRAGMENT_SHADER, fs)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, v)
        GLES20.glAttachShader(p, f)
        GLES20.glLinkProgram(p)
        val status = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) { Log2.e("program link: " + GLES20.glGetProgramInfoLog(p)) }
        return p
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val status = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) { Log2.e("shader compile: " + GLES20.glGetShaderInfoLog(s)) }
        return s
    }

    private companion object {
        const val EGL_RECORDABLE_ANDROID = 0x3142

        const val VERTEX_SHADER = """
            attribute vec4 aPos;
            attribute vec4 aTex;
            uniform mat4 uStMatrix;
            varying vec2 vTex;
            void main() {
                gl_Position = aPos;
                vTex = (uStMatrix * aTex).xy;
            }
        """

        const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTex;
            uniform samplerExternalOES sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, vTex);
            }
        """
    }
}
