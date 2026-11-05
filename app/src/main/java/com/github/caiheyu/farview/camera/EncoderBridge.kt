package com.github.caiheyu.farview.camera

import android.graphics.SurfaceTexture
import android.opengl.*
import android.os.Handler
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Normalize camera timestamps before encoding. Some legacy HALs mix clock domains. */
class EncoderBridge(width: Int, height: Int, output: Surface, handler: Handler, private val error: (String) -> Unit) : AutoCloseable {
    private val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    private val eglContext: android.opengl.EGLContext
    private val window: android.opengl.EGLSurface
    private val program: Int
    private val texture: SurfaceTexture
    val surface: Surface
    private var closed = false
    private val vertices = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f, -1f, 0f, 0f, 1f, -1f, 1f, 0f, -1f, 1f, 0f, 1f, 1f, 1f, 1f, 1f)); position(0)
    }
    private val matrix = FloatArray(16)
    init {
        check(EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 1)) { "无法初始化视频绘制" }
        val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, intArrayOf(EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, 0x3142, 1, EGL14.EGL_NONE), 0, configs, 0, 1, count, 0) && count[0] > 0)
        eglContext = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        window = EGL14.eglCreateWindowSurface(display, configs[0], output, intArrayOf(EGL14.EGL_NONE), 0)
        check(EGL14.eglMakeCurrent(display, window, window, eglContext))
        fun shader(type: Int, source: String): Int = GLES20.glCreateShader(type).also {
            GLES20.glShaderSource(it, source); GLES20.glCompileShader(it)
            val ok = IntArray(1); GLES20.glGetShaderiv(it, GLES20.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { GLES20.glGetShaderInfoLog(it) }
        }
        val vertex = shader(GLES20.GL_VERTEX_SHADER, "attribute vec2 position; attribute vec2 uv; uniform mat4 transform; varying vec2 texCoord; void main(){gl_Position=vec4(position,0.,1.); texCoord=(transform*vec4(uv,0.,1.)).xy;}")
        val fragment = shader(GLES20.GL_FRAGMENT_SHADER, "#extension GL_OES_EGL_image_external : require\nprecision mediump float; uniform samplerExternalOES image; varying vec2 texCoord; void main(){gl_FragColor=texture2D(image,texCoord);}")
        program = GLES20.glCreateProgram().also { GLES20.glAttachShader(it, vertex); GLES20.glAttachShader(it, fragment); GLES20.glLinkProgram(it) }
        GLES20.glDeleteShader(vertex); GLES20.glDeleteShader(fragment)
        val textures = IntArray(1); GLES20.glGenTextures(1, textures, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textures[0])
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        texture = SurfaceTexture(textures[0]).apply { setDefaultBufferSize(width, height) }
        surface = Surface(texture)
        texture.setOnFrameAvailableListener({
            if (!closed) try {
                EGL14.eglMakeCurrent(display, window, window, eglContext)
                texture.updateTexImage(); texture.getTransformMatrix(matrix)
                GLES20.glViewport(0, 0, width, height); GLES20.glUseProgram(program)
                val position = GLES20.glGetAttribLocation(program, "position")
                val uv = GLES20.glGetAttribLocation(program, "uv")
                vertices.position(0); GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 16, vertices); GLES20.glEnableVertexAttribArray(position)
                vertices.position(2); GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, 16, vertices); GLES20.glEnableVertexAttribArray(uv)
                GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "transform"), 1, false, matrix, 0)
                GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "image"), 0)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                EGLExt.eglPresentationTimeANDROID(display, window, System.nanoTime())
                check(EGL14.eglSwapBuffers(display, window))
            } catch (e: Exception) { if (!closed) error("视频绘制失败：${e.message}") }
        }, handler)
    }
    override fun close() {
        closed = true; texture.setOnFrameAvailableListener(null); surface.release(); texture.release()
        EGL14.eglMakeCurrent(display, window, window, eglContext); GLES20.glDeleteProgram(program)
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display, window); EGL14.eglDestroyContext(display, eglContext); EGL14.eglReleaseThread(); EGL14.eglTerminate(display)
    }
}
