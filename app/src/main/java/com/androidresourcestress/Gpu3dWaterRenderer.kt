package com.androidresourcestress

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.SystemClock
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Phase 0 renderer. It deliberately owns no Activity or View reference so the
 * EGL thread cannot retain UI objects across Activity recreation.
 */
class Gpu3dWaterRenderer : GLSurfaceView.Renderer {
    @Volatile
    private var requestedConfiguration = Gpu3dStressConfiguration()

    @Volatile
    private var running = false

    @Volatile
    private var latestMetrics = Gpu3dStressMetrics()

    private var surfaceWidth = 1
    private var surfaceHeight = 1
    private var resourcesReady = false
    private var activeConfiguration: Gpu3dStressConfiguration? = null

    private var waterProgram = 0
    private var geometryProgram = 0
    private var blitProgram = 0
    private var waterVertexBuffer = 0
    private var waterIndexBuffer = 0
    private var waterIndexCount = 0
    private var geometryVertexBuffer = 0
    private var geometryVertexCount = 0
    private var framebuffer = 0
    private var colorTexture = 0
    private var depthBuffer = 0

    private val projection = FloatArray(16)
    private val view = FloatArray(16)
    private val viewProjection = FloatArray(16)
    private var startNanos = 0L
    private var lastFrameNanos = 0L
    private var fpsWindowStartNanos = 0L
    private var fpsWindowFrames = 0L
    private var renderedFrames = 0L
    private var measuredFps = 0.0
    private var smoothedFrameTimeMs = 0.0

    fun start(configuration: Gpu3dStressConfiguration) {
        requestedConfiguration = configuration
        startNanos = SystemClock.elapsedRealtimeNanos()
        lastFrameNanos = 0L
        fpsWindowStartNanos = 0L
        fpsWindowFrames = 0L
        renderedFrames = 0L
        measuredFps = 0.0
        smoothedFrameTimeMs = 0.0
        running = true
        latestMetrics = Gpu3dStressMetrics(
            running = true,
            resolution = configuration.resolution,
            fpsLimit = configuration.fpsLimit,
        )
    }

    /** Must be invoked on the GLSurfaceView rendering thread. */
    fun stopAndRelease() {
        running = false
        releaseGlResources()
        latestMetrics = latestMetrics.copy(running = false)
    }

    fun markStopped() {
        running = false
        latestMetrics = latestMetrics.copy(running = false)
    }

    fun snapshot(): Gpu3dStressMetrics = latestMetrics

    override fun onSurfaceCreated(gl: javax.microedition.khronos.opengles.GL10?, config: javax.microedition.khronos.egl.EGLConfig?) {
        // A recreated EGL context invalidates every previous OpenGL object id.
        clearObjectIds()
        GLES30.glClearColor(0.005f, 0.012f, 0.035f, 1f)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthFunc(GLES30.GL_LEQUAL)
    }

    override fun onSurfaceChanged(gl: javax.microedition.khronos.opengles.GL10?, width: Int, height: Int) {
        surfaceWidth = max(1, width)
        surfaceHeight = max(1, height)
    }

    override fun onDrawFrame(gl: javax.microedition.khronos.opengles.GL10?) {
        if (!running) {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            GLES30.glViewport(0, 0, surfaceWidth, surfaceHeight)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
            return
        }
        val configuration = requestedConfiguration
        try {
            if (!resourcesReady || activeConfiguration != configuration) {
                releaseGlResources()
                createGlResources(configuration)
                activeConfiguration = configuration
                resourcesReady = true
            }
            val now = SystemClock.elapsedRealtimeNanos()
            val elapsedSeconds = (now - startNanos).coerceAtLeast(0L) / 1_000_000_000f
            updateCamera(elapsedSeconds, configuration.resolution)
            renderScene(configuration, elapsedSeconds)
            updateMetrics(now, configuration)
        } catch (error: Throwable) {
            val message = error.message ?: error.javaClass.simpleName
            running = false
            releaseGlResources()
            latestMetrics = latestMetrics.copy(running = false, lastError = message)
        }
    }

    private fun createGlResources(configuration: Gpu3dStressConfiguration) {
        waterProgram = createProgram(WATER_VERTEX_SHADER, WATER_FRAGMENT_SHADER)
        geometryProgram = createProgram(GEOMETRY_VERTEX_SHADER, GEOMETRY_FRAGMENT_SHADER)
        blitProgram = createProgram(BLIT_VERTEX_SHADER, BLIT_FRAGMENT_SHADER)
        createWaterMesh()
        createGeometryMesh()
        createFramebuffer(configuration.resolution)
        checkGl("create Phase 0 resources")
    }

    private fun createWaterMesh() {
        val columns = 128
        val rows = 72
        val vertices = FloatArray((columns + 1) * (rows + 1) * 2)
        var vertexOffset = 0
        for (row in 0..rows) {
            val z = (row.toFloat() / rows - 0.5f) * 30f
            for (column in 0..columns) {
                val x = (column.toFloat() / columns - 0.5f) * 40f
                vertices[vertexOffset++] = x
                vertices[vertexOffset++] = z
            }
        }
        val indices = IntArray(columns * rows * 6)
        var indexOffset = 0
        for (row in 0 until rows) {
            for (column in 0 until columns) {
                val topLeft = row * (columns + 1) + column
                val bottomLeft = (row + 1) * (columns + 1) + column
                indices[indexOffset++] = topLeft
                indices[indexOffset++] = bottomLeft
                indices[indexOffset++] = topLeft + 1
                indices[indexOffset++] = topLeft + 1
                indices[indexOffset++] = bottomLeft
                indices[indexOffset++] = bottomLeft + 1
            }
        }
        waterIndexCount = indices.size
        waterVertexBuffer = createBuffer(GLES30.GL_ARRAY_BUFFER, vertices.toBuffer())
        waterIndexBuffer = createBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, indices.toBuffer())
    }

    private fun createGeometryMesh() {
        val values = ArrayList<Float>(36 * 6)
        fun face(normal: FloatArray, a: FloatArray, b: FloatArray, c: FloatArray, d: FloatArray) {
            listOf(a, b, c, a, c, d).forEach { point ->
                values += point[0]
                values += point[1]
                values += point[2]
                values += normal[0]
                values += normal[1]
                values += normal[2]
            }
        }
        val n = 0.5f
        face(floatArrayOf(0f, 0f, 1f), floatArrayOf(-n, -n, n), floatArrayOf(n, -n, n), floatArrayOf(n, n, n), floatArrayOf(-n, n, n))
        face(floatArrayOf(0f, 0f, -1f), floatArrayOf(n, -n, -n), floatArrayOf(-n, -n, -n), floatArrayOf(-n, n, -n), floatArrayOf(n, n, -n))
        face(floatArrayOf(1f, 0f, 0f), floatArrayOf(n, -n, n), floatArrayOf(n, -n, -n), floatArrayOf(n, n, -n), floatArrayOf(n, n, n))
        face(floatArrayOf(-1f, 0f, 0f), floatArrayOf(-n, -n, -n), floatArrayOf(-n, -n, n), floatArrayOf(-n, n, n), floatArrayOf(-n, n, -n))
        face(floatArrayOf(0f, 1f, 0f), floatArrayOf(-n, n, n), floatArrayOf(n, n, n), floatArrayOf(n, n, -n), floatArrayOf(-n, n, -n))
        face(floatArrayOf(0f, -1f, 0f), floatArrayOf(-n, -n, -n), floatArrayOf(n, -n, -n), floatArrayOf(n, -n, n), floatArrayOf(-n, -n, n))
        geometryVertexCount = values.size / 6
        geometryVertexBuffer = createBuffer(
            GLES30.GL_ARRAY_BUFFER,
            values.toFloatArray().toBuffer(),
        )
    }

    private fun createFramebuffer(resolution: Gpu3dResolution) {
        val maxTextureSize = IntArray(1)
        GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, maxTextureSize, 0)
        require(resolution.width <= maxTextureSize[0] && resolution.height <= maxTextureSize[0]) {
            "${resolution.width}x${resolution.height} exceeds GL_MAX_TEXTURE_SIZE=${maxTextureSize[0]}"
        }
        colorTexture = generatedTexture()
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, colorTexture)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D,
            0,
            GLES30.GL_RGBA8,
            resolution.width,
            resolution.height,
            0,
            GLES30.GL_RGBA,
            GLES30.GL_UNSIGNED_BYTE,
            null,
        )
        val renderbuffers = IntArray(1)
        GLES30.glGenRenderbuffers(1, renderbuffers, 0)
        depthBuffer = renderbuffers[0]
        GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, depthBuffer)
        GLES30.glRenderbufferStorage(
            GLES30.GL_RENDERBUFFER,
            GLES30.GL_DEPTH_COMPONENT24,
            resolution.width,
            resolution.height,
        )
        val framebuffers = IntArray(1)
        GLES30.glGenFramebuffers(1, framebuffers, 0)
        framebuffer = framebuffers[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER,
            GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D,
            colorTexture,
            0,
        )
        GLES30.glFramebufferRenderbuffer(
            GLES30.GL_FRAMEBUFFER,
            GLES30.GL_DEPTH_ATTACHMENT,
            GLES30.GL_RENDERBUFFER,
            depthBuffer,
        )
        check(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE) {
            "Unable to create ${resolution.width}x${resolution.height} render target"
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    private fun updateCamera(elapsedSeconds: Float, resolution: Gpu3dResolution) {
        val cameraX = sin(elapsedSeconds * 0.19f) * 7.5f
        val cameraZ = 10.5f + cos(elapsedSeconds * 0.13f) * 3.5f
        val cameraY = 3.4f + sin(elapsedSeconds * 0.31f) * 0.45f
        Matrix.setLookAtM(view, 0, cameraX, cameraY, cameraZ, 0f, 0f, -2.5f, 0f, 1f, 0f)
        Matrix.perspectiveM(
            projection,
            0,
            55f,
            resolution.width.toFloat() / resolution.height,
            0.1f,
            80f,
        )
        Matrix.multiplyMM(viewProjection, 0, projection, 0, view, 0)
    }

    private fun renderScene(configuration: Gpu3dStressConfiguration, elapsedSeconds: Float) {
        val resolution = configuration.resolution
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
        GLES30.glViewport(0, 0, resolution.width, resolution.height)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glClearColor(0.025f, 0.085f, 0.16f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

        GLES30.glUseProgram(waterProgram)
        uniformMatrix(waterProgram, "uViewProjection", viewProjection)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(waterProgram, "uTime"), elapsedSeconds)
        GLES30.glUniform3f(
            GLES30.glGetUniformLocation(waterProgram, "uCamera"),
            sin(elapsedSeconds * 0.19f) * 7.5f,
            3.4f + sin(elapsedSeconds * 0.31f) * 0.45f,
            10.5f + cos(elapsedSeconds * 0.13f) * 3.5f,
        )
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, waterVertexBuffer)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 2 * Float.SIZE_BYTES, 0)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, waterIndexBuffer)
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, waterIndexCount, GLES30.GL_UNSIGNED_INT, 0)

        GLES30.glUseProgram(geometryProgram)
        uniformMatrix(geometryProgram, "uViewProjection", viewProjection)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(geometryProgram, "uTime"), elapsedSeconds)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, geometryVertexBuffer)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 6 * Float.SIZE_BYTES, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, 6 * Float.SIZE_BYTES, 3 * Float.SIZE_BYTES)
        GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLES, 0, geometryVertexCount, 18)

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glViewport(0, 0, surfaceWidth, surfaceHeight)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        val sourceAspect = resolution.width.toFloat() / resolution.height
        val surfaceAspect = surfaceWidth.toFloat() / surfaceHeight
        val viewportWidth: Int
        val viewportHeight: Int
        if (surfaceAspect > sourceAspect) {
            viewportHeight = surfaceHeight
            viewportWidth = (surfaceHeight * sourceAspect).toInt()
        } else {
            viewportWidth = surfaceWidth
            viewportHeight = (surfaceWidth / sourceAspect).toInt()
        }
        GLES30.glViewport(
            (surfaceWidth - viewportWidth) / 2,
            (surfaceHeight - viewportHeight) / 2,
            viewportWidth,
            viewportHeight,
        )
        GLES30.glUseProgram(blitProgram)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, colorTexture)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(blitProgram, "uTexture"), 0)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        checkGl("render Phase 0 frame")
    }

    private fun updateMetrics(now: Long, configuration: Gpu3dStressConfiguration) {
        if (lastFrameNanos > 0L) {
            val frameTimeMs = (now - lastFrameNanos).coerceAtLeast(0L) / 1_000_000.0
            smoothedFrameTimeMs = if (smoothedFrameTimeMs == 0.0) {
                frameTimeMs
            } else {
                smoothedFrameTimeMs * 0.85 + frameTimeMs * 0.15
            }
        }
        lastFrameNanos = now
        if (fpsWindowStartNanos == 0L) fpsWindowStartNanos = now
        fpsWindowFrames += 1L
        renderedFrames += 1L
        val fpsWindow = now - fpsWindowStartNanos
        if (fpsWindow >= 1_000_000_000L) {
            measuredFps = fpsWindowFrames * 1_000_000_000.0 / fpsWindow
            fpsWindowFrames = 0L
            fpsWindowStartNanos = now
        }
        latestMetrics = Gpu3dStressMetrics(
            running = true,
            currentFps = measuredFps,
            frameTimeMs = smoothedFrameTimeMs,
            runtimeMs = (now - startNanos).coerceAtLeast(0L) / 1_000_000L,
            renderedFrames = renderedFrames,
            resolution = configuration.resolution,
            fpsLimit = configuration.fpsLimit,
        )
    }

    private fun releaseGlResources() {
        if (waterVertexBuffer != 0 || waterIndexBuffer != 0 || geometryVertexBuffer != 0) {
            GLES30.glDeleteBuffers(
                3,
                intArrayOf(waterVertexBuffer, waterIndexBuffer, geometryVertexBuffer),
                0,
            )
        }
        if (waterProgram != 0) GLES30.glDeleteProgram(waterProgram)
        if (geometryProgram != 0) GLES30.glDeleteProgram(geometryProgram)
        if (blitProgram != 0) GLES30.glDeleteProgram(blitProgram)
        if (colorTexture != 0) GLES30.glDeleteTextures(1, intArrayOf(colorTexture), 0)
        if (depthBuffer != 0) GLES30.glDeleteRenderbuffers(1, intArrayOf(depthBuffer), 0)
        if (framebuffer != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
        clearObjectIds()
    }

    private fun clearObjectIds() {
        waterProgram = 0
        geometryProgram = 0
        blitProgram = 0
        waterVertexBuffer = 0
        waterIndexBuffer = 0
        geometryVertexBuffer = 0
        framebuffer = 0
        colorTexture = 0
        depthBuffer = 0
        resourcesReady = false
        activeConfiguration = null
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertex = compileShader(GLES30.GL_VERTEX_SHADER, vertexSource)
        val fragment = compileShader(GLES30.GL_FRAGMENT_SHADER, fragmentSource)
        val program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, vertex)
        GLES30.glAttachShader(program, fragment)
        GLES30.glLinkProgram(program)
        GLES30.glDeleteShader(vertex)
        GLES30.glDeleteShader(fragment)
        val status = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val message = GLES30.glGetProgramInfoLog(program)
            GLES30.glDeleteProgram(program)
            error("OpenGL program link failed: $message")
        }
        return program
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val message = GLES30.glGetShaderInfoLog(shader)
            GLES30.glDeleteShader(shader)
            error("OpenGL shader compile failed: $message")
        }
        return shader
    }

    private fun createBuffer(target: Int, data: java.nio.Buffer): Int {
        val ids = IntArray(1)
        GLES30.glGenBuffers(1, ids, 0)
        GLES30.glBindBuffer(target, ids[0])
        val bytes = when (data) {
            is FloatBuffer -> data.remaining() * Float.SIZE_BYTES
            is IntBuffer -> data.remaining() * Int.SIZE_BYTES
            else -> error("Unsupported buffer type")
        }
        GLES30.glBufferData(target, bytes, data, GLES30.GL_STATIC_DRAW)
        return ids[0]
    }

    private fun generatedTexture(): Int {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        return ids[0]
    }

    private fun uniformMatrix(program: Int, name: String, value: FloatArray) {
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(program, name), 1, false, value, 0)
    }

    private fun checkGl(operation: String) {
        val error = GLES30.glGetError()
        check(error == GLES30.GL_NO_ERROR) { "$operation failed with OpenGL error 0x${error.toString(16)}" }
    }

    private fun FloatArray.toBuffer(): FloatBuffer = ByteBuffer
        .allocateDirect(size * Float.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply { put(this@toBuffer).position(0) }

    private fun IntArray.toBuffer(): IntBuffer = ByteBuffer
        .allocateDirect(size * Int.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
        .asIntBuffer()
        .apply { put(this@toBuffer).position(0) }

    private companion object {
        const val WATER_VERTEX_SHADER = """#version 300 es
layout(location = 0) in vec2 aPosition;
uniform mat4 uViewProjection;
uniform float uTime;
out highp vec3 vWorldPosition;
out highp vec3 vNormal;

void main() {
    float waveA = sin(aPosition.x * 0.72 + uTime * 1.45) * 0.28;
    float waveB = cos(aPosition.y * 0.91 - uTime * 1.12) * 0.21;
    float waveC = sin((aPosition.x + aPosition.y) * 0.46 + uTime * 0.78) * 0.15;
    float height = waveA + waveB + waveC;
    float dx = cos(aPosition.x * 0.72 + uTime * 1.45) * 0.2016
        + cos((aPosition.x + aPosition.y) * 0.46 + uTime * 0.78) * 0.069;
    float dz = -sin(aPosition.y * 0.91 - uTime * 1.12) * 0.1911
        + cos((aPosition.x + aPosition.y) * 0.46 + uTime * 0.78) * 0.069;
    vWorldPosition = vec3(aPosition.x, height, aPosition.y);
    vNormal = normalize(vec3(-dx, 1.0, -dz));
    gl_Position = uViewProjection * vec4(vWorldPosition, 1.0);
}
"""

        const val WATER_FRAGMENT_SHADER = """#version 300 es
precision highp float;
in highp vec3 vWorldPosition;
in highp vec3 vNormal;
uniform float uTime;
uniform vec3 uCamera;
out vec4 outputColor;

void main() {
    vec3 normal = vNormal;
    float detail = 0.0;
    for (int i = 0; i < 10; ++i) {
        float fi = float(i);
        float phase = vWorldPosition.x * (0.23 + fi * 0.037)
            + vWorldPosition.z * (0.19 + fi * 0.029)
            + uTime * (0.44 + fi * 0.021);
        detail += sin(phase) * cos(phase * 1.37 + fi) * 0.018;
    }
    normal = normalize(normal + vec3(detail, 0.0, -detail * 0.8));
    vec3 lightDirection = normalize(vec3(-0.35, 0.82, 0.41));
    vec3 viewDirection = normalize(uCamera - vWorldPosition);
    vec3 halfDirection = normalize(lightDirection + viewDirection);
    float diffuse = max(dot(normal, lightDirection), 0.0);
    float specular = pow(max(dot(normal, halfDirection), 0.0), 72.0);
    float fresnel = pow(1.0 - max(dot(normal, viewDirection), 0.0), 3.0);
    float foam = smoothstep(0.33, 0.52, abs(vWorldPosition.y));
    vec3 deep = vec3(0.005, 0.08, 0.19);
    vec3 shallow = vec3(0.015, 0.42, 0.58);
    vec3 color = mix(deep, shallow, diffuse * 0.72 + 0.16);
    color += vec3(0.34, 0.68, 0.92) * fresnel * 0.58;
    color += vec3(1.0, 0.86, 0.58) * specular * 1.35;
    color += vec3(0.55, 0.84, 0.92) * foam * 0.16;
    outputColor = vec4(color, 1.0);
}
"""

        const val GEOMETRY_VERTEX_SHADER = """#version 300 es
layout(location = 0) in vec3 aPosition;
layout(location = 1) in vec3 aNormal;
uniform mat4 uViewProjection;
uniform float uTime;
out highp vec3 vNormal;
out highp vec3 vColor;

void main() {
    int column = gl_InstanceID % 6;
    int row = gl_InstanceID / 6;
    float angle = uTime * (0.31 + float(gl_InstanceID % 4) * 0.07)
        + float(gl_InstanceID) * 0.63;
    mat2 rotation = mat2(cos(angle), -sin(angle), sin(angle), cos(angle));
    float scale = 0.44 + float(gl_InstanceID % 3) * 0.12;
    vec3 local = aPosition * vec3(scale, 1.1 + scale, scale);
    local.xz = rotation * local.xz;
    vec3 offset = vec3((float(column) - 2.5) * 4.6, 0.75, -10.5 + float(row) * 8.5);
    offset.y += sin(uTime * 1.45 + float(gl_InstanceID)) * 0.18;
    vec3 world = local + offset;
    vNormal = normalize(vec3(rotation * aNormal.xz, aNormal.y).xzy);
    vColor = mix(vec3(1.0, 0.18, 0.035), vec3(1.0, 0.72, 0.08), float(gl_InstanceID % 5) / 4.0);
    gl_Position = uViewProjection * vec4(world, 1.0);
}
"""

        const val GEOMETRY_FRAGMENT_SHADER = """#version 300 es
precision highp float;
in highp vec3 vNormal;
in highp vec3 vColor;
out vec4 outputColor;
void main() {
    float lighting = 0.22 + 0.78 * max(dot(normalize(vNormal), normalize(vec3(-0.35, 0.82, 0.41))), 0.0);
    outputColor = vec4(vColor * lighting, 1.0);
}
"""

        const val BLIT_VERTEX_SHADER = """#version 300 es
out highp vec2 vUv;
void main() {
    vec2 positions[3] = vec2[](vec2(-1.0, -1.0), vec2(3.0, -1.0), vec2(-1.0, 3.0));
    vec2 position = positions[gl_VertexID];
    gl_Position = vec4(position, 0.0, 1.0);
    vUv = position * 0.5 + 0.5;
}
"""

        const val BLIT_FRAGMENT_SHADER = """#version 300 es
precision mediump float;
in highp vec2 vUv;
uniform sampler2D uTexture;
out vec4 outputColor;
void main() {
    outputColor = texture(uTexture, vUv);
}
"""
    }
}
