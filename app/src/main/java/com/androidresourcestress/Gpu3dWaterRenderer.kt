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
import kotlin.math.sqrt

/**
 * Phase 1 Water Race renderer. It owns no Activity or View reference, keeping
 * the EGL thread independent from Activity recreation and lifecycle teardown.
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
    private var sceneProgram = 0
    private var particleProgram = 0
    private var blitProgram = 0
    private var waterVertexBuffer = 0
    private var waterIndexBuffer = 0
    private var waterIndexCount = 0
    private var geometryVertexBuffer = 0
    private var geometryVertexCount = 0
    private var framebuffer = 0
    private var colorTexture = 0
    private var depthBuffer = 0
    private var shadowFramebuffer = 0
    private var shadowTexture = 0
    private var shadowDepthBuffer = 0

    private val projection = FloatArray(16)
    private val view = FloatArray(16)
    private val viewProjection = FloatArray(16)
    private val lightProjection = FloatArray(16)
    private val lightView = FloatArray(16)
    private val lightViewProjection = FloatArray(16)
    private val cameraPosition = FloatArray(3)
    private val racerPosition = FloatArray(3)
    private val lightDirection = FloatArray(3)
    private val frameStatistics = Gpu3dFrameStatistics()
    private var startNanos = 0L

    fun start(configuration: Gpu3dStressConfiguration) {
        requestedConfiguration = configuration
        startNanos = SystemClock.elapsedRealtimeNanos()
        frameStatistics.reset()
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

    override fun onSurfaceCreated(
        gl: javax.microedition.khronos.opengles.GL10?,
        config: javax.microedition.khronos.egl.EGLConfig?,
    ) {
        // A recreated EGL context invalidates every previous OpenGL object id.
        clearObjectIds()
        GLES30.glClearColor(0.005f, 0.012f, 0.035f, 1f)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthFunc(GLES30.GL_LEQUAL)
    }

    override fun onSurfaceChanged(
        gl: javax.microedition.khronos.opengles.GL10?,
        width: Int,
        height: Int,
    ) {
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
            updateSceneTransforms(elapsedSeconds, configuration.resolution)
            renderShadowMap(elapsedSeconds)
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
        sceneProgram = createProgram(SCENE_VERTEX_SHADER, SCENE_FRAGMENT_SHADER)
        particleProgram = createProgram(PARTICLE_VERTEX_SHADER, PARTICLE_FRAGMENT_SHADER)
        blitProgram = createProgram(BLIT_VERTEX_SHADER, BLIT_FRAGMENT_SHADER)
        createWaterMesh()
        createGeometryMesh()
        createFramebuffer(configuration.resolution)
        createShadowFramebuffer()
        checkGl("create Phase 1 Water Race resources")
    }

    private fun createWaterMesh() {
        val columns = 192
        val rows = 128
        val vertices = FloatArray((columns + 1) * (rows + 1) * 2)
        var vertexOffset = 0
        for (row in 0..rows) {
            val z = (row.toFloat() / rows - 0.5f) * WATER_DEPTH
            for (column in 0..columns) {
                val x = (column.toFloat() / columns - 0.5f) * WATER_WIDTH
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
        fun face(
            normal: FloatArray,
            a: FloatArray,
            b: FloatArray,
            c: FloatArray,
            d: FloatArray,
        ) {
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
            resolution.width.toString() + "x" + resolution.height +
                " exceeds GL_MAX_TEXTURE_SIZE=" + maxTextureSize[0]
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
        depthBuffer = generatedRenderbuffer()
        GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, depthBuffer)
        GLES30.glRenderbufferStorage(
            GLES30.GL_RENDERBUFFER,
            GLES30.GL_DEPTH_COMPONENT24,
            resolution.width,
            resolution.height,
        )
        framebuffer = generatedFramebuffer()
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
            "Unable to create " + resolution.width + "x" + resolution.height + " render target"
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    /**
     * RGBA8 is used for packed shadow depth because it is color-renderable on
     * every OpenGL ES 3.0 device; no optional floating-point extension is
     * required.
     */
    private fun createShadowFramebuffer() {
        shadowTexture = generatedTexture()
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, shadowTexture)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D,
            0,
            GLES30.GL_RGBA8,
            SHADOW_MAP_SIZE,
            SHADOW_MAP_SIZE,
            0,
            GLES30.GL_RGBA,
            GLES30.GL_UNSIGNED_BYTE,
            null,
        )
        shadowDepthBuffer = generatedRenderbuffer()
        GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, shadowDepthBuffer)
        GLES30.glRenderbufferStorage(
            GLES30.GL_RENDERBUFFER,
            GLES30.GL_DEPTH_COMPONENT24,
            SHADOW_MAP_SIZE,
            SHADOW_MAP_SIZE,
        )
        shadowFramebuffer = generatedFramebuffer()
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, shadowFramebuffer)
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER,
            GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D,
            shadowTexture,
            0,
        )
        GLES30.glFramebufferRenderbuffer(
            GLES30.GL_FRAMEBUFFER,
            GLES30.GL_DEPTH_ATTACHMENT,
            GLES30.GL_RENDERBUFFER,
            shadowDepthBuffer,
        )
        check(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE) {
            "Unable to create Water Race shadow map"
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    private fun updateSceneTransforms(elapsedSeconds: Float, resolution: Gpu3dResolution) {
        val raceAngle = elapsedSeconds * TRACK_SPEED
        racerPosition[0] = cos(raceAngle) * TRACK_RADIUS_X
        racerPosition[1] = 0.68f
        racerPosition[2] = sin(raceAngle) * TRACK_RADIUS_Z

        var tangentX = -sin(raceAngle) * TRACK_RADIUS_X
        var tangentZ = cos(raceAngle) * TRACK_RADIUS_Z
        val tangentLength = sqrt(tangentX * tangentX + tangentZ * tangentZ)
        tangentX /= tangentLength
        tangentZ /= tangentLength
        val rightX = tangentZ
        val rightZ = -tangentX
        val cameraSway = sin(elapsedSeconds * 0.31f) * 0.8f
        cameraPosition[0] = racerPosition[0] - tangentX * 7.2f + rightX * cameraSway
        cameraPosition[1] = 4.2f + sin(elapsedSeconds * 0.47f) * 0.18f
        cameraPosition[2] = racerPosition[2] - tangentZ * 7.2f + rightZ * cameraSway
        Matrix.setLookAtM(
            view,
            0,
            cameraPosition[0],
            cameraPosition[1],
            cameraPosition[2],
            racerPosition[0] + tangentX * 4.2f,
            0.7f,
            racerPosition[2] + tangentZ * 4.2f,
            0f,
            1f,
            0f,
        )
        Matrix.perspectiveM(
            projection,
            0,
            58f,
            resolution.width.toFloat() / resolution.height,
            0.15f,
            110f,
        )
        Matrix.multiplyMM(viewProjection, 0, projection, 0, view, 0)

        val lightAngle = elapsedSeconds * 0.055f
        lightDirection[0] = cos(lightAngle) * 0.43f
        lightDirection[1] = 0.82f
        lightDirection[2] = sin(lightAngle) * 0.43f
        val lightLength = sqrt(
            lightDirection[0] * lightDirection[0] +
                lightDirection[1] * lightDirection[1] +
                lightDirection[2] * lightDirection[2],
        )
        lightDirection.indices.forEach { lightDirection[it] /= lightLength }
        Matrix.setLookAtM(
            lightView,
            0,
            lightDirection[0] * 45f,
            lightDirection[1] * 45f,
            lightDirection[2] * 45f,
            0f,
            0f,
            0f,
            0f,
            1f,
            0f,
        )
        Matrix.orthoM(lightProjection, 0, -30f, 30f, -24f, 24f, 1f, 90f)
        Matrix.multiplyMM(lightViewProjection, 0, lightProjection, 0, lightView, 0)
    }

    private fun renderShadowMap(elapsedSeconds: Float) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, shadowFramebuffer)
        GLES30.glViewport(0, 0, SHADOW_MAP_SIZE, SHADOW_MAP_SIZE)
        GLES30.glClearColor(1f, 1f, 1f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_CULL_FACE)
        GLES30.glCullFace(GLES30.GL_BACK)
        drawSceneGeometry(elapsedSeconds, lightViewProjection, true)
    }

    private fun renderScene(configuration: Gpu3dStressConfiguration, elapsedSeconds: Float) {
        val resolution = configuration.resolution
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
        GLES30.glViewport(0, 0, resolution.width, resolution.height)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        GLES30.glClearColor(0.018f, 0.09f, 0.19f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

        GLES30.glUseProgram(waterProgram)
        uniformMatrix(waterProgram, "uViewProjection", viewProjection)
        uniformMatrix(waterProgram, "uLightViewProjection", lightViewProjection)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(waterProgram, "uTime"), elapsedSeconds)
        uniform3(waterProgram, "uCamera", cameraPosition)
        uniform3(waterProgram, "uLightDirection", lightDirection)
        uniform3(waterProgram, "uRacerPosition", racerPosition)
        bindShadowTexture(waterProgram)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, waterVertexBuffer)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 2 * Float.SIZE_BYTES, 0)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, waterIndexBuffer)
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, waterIndexCount, GLES30.GL_UNSIGNED_INT, 0)

        GLES30.glEnable(GLES30.GL_CULL_FACE)
        GLES30.glCullFace(GLES30.GL_BACK)
        drawSceneGeometry(elapsedSeconds, viewProjection, false)
        renderSplashParticles(elapsedSeconds)
        blitToSurface(resolution)
        checkGl("render Phase 1 Water Race frame")
    }

    private fun drawSceneGeometry(
        elapsedSeconds: Float,
        activeViewProjection: FloatArray,
        shadowPass: Boolean,
    ) {
        GLES30.glUseProgram(sceneProgram)
        uniformMatrix(sceneProgram, "uViewProjection", activeViewProjection)
        uniformMatrix(sceneProgram, "uLightViewProjection", lightViewProjection)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(sceneProgram, "uTime"), elapsedSeconds)
        GLES30.glUniform1i(
            GLES30.glGetUniformLocation(sceneProgram, "uShadowPass"),
            if (shadowPass) 1 else 0,
        )
        uniform3(sceneProgram, "uLightDirection", lightDirection)
        if (!shadowPass) bindShadowTexture(sceneProgram)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, geometryVertexBuffer)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 6 * Float.SIZE_BYTES, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, 6 * Float.SIZE_BYTES, 3 * Float.SIZE_BYTES)
        SCENE_GROUP_COUNTS.forEachIndexed { group, count ->
            GLES30.glUniform1i(GLES30.glGetUniformLocation(sceneProgram, "uGroup"), group)
            GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLES, 0, geometryVertexCount, count)
        }
    }

    private fun renderSplashParticles(elapsedSeconds: Float) {
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glDepthMask(false)
        GLES30.glUseProgram(particleProgram)
        uniformMatrix(particleProgram, "uViewProjection", viewProjection)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(particleProgram, "uTime"), elapsedSeconds)
        GLES30.glDrawArrays(GLES30.GL_POINTS, 0, SPLASH_PARTICLE_COUNT)
        GLES30.glDepthMask(true)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    private fun blitToSurface(resolution: Gpu3dResolution) {
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
    }

    private fun bindShadowTexture(program: Int) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, shadowTexture)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uShadowMap"), 1)
        GLES30.glUniform2f(
            GLES30.glGetUniformLocation(program, "uShadowTexel"),
            1f / SHADOW_MAP_SIZE,
            1f / SHADOW_MAP_SIZE,
        )
    }

    private fun updateMetrics(now: Long, configuration: Gpu3dStressConfiguration) {
        val statistics = frameStatistics.recordFrame(now)
        latestMetrics = Gpu3dStressMetrics(
            running = true,
            currentFps = statistics.currentFps,
            averageFps = statistics.averageFps,
            minimumFps = statistics.minimumFps,
            frameTimeMs = statistics.frameTimeMs,
            maximumFrameTimeMs = statistics.maximumFrameTimeMs,
            runtimeMs = (now - startNanos).coerceAtLeast(0L) / 1_000_000L,
            renderedFrames = statistics.renderedFrames,
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
        intArrayOf(waterProgram, sceneProgram, particleProgram, blitProgram)
            .filter { it != 0 }
            .forEach(GLES30::glDeleteProgram)
        val textures = intArrayOf(colorTexture, shadowTexture).filter { it != 0 }.toIntArray()
        if (textures.isNotEmpty()) GLES30.glDeleteTextures(textures.size, textures, 0)
        val renderbuffers = intArrayOf(depthBuffer, shadowDepthBuffer).filter { it != 0 }.toIntArray()
        if (renderbuffers.isNotEmpty()) {
            GLES30.glDeleteRenderbuffers(renderbuffers.size, renderbuffers, 0)
        }
        val framebuffers = intArrayOf(framebuffer, shadowFramebuffer).filter { it != 0 }.toIntArray()
        if (framebuffers.isNotEmpty()) {
            GLES30.glDeleteFramebuffers(framebuffers.size, framebuffers, 0)
        }
        clearObjectIds()
    }

    private fun clearObjectIds() {
        waterProgram = 0
        sceneProgram = 0
        particleProgram = 0
        blitProgram = 0
        waterVertexBuffer = 0
        waterIndexBuffer = 0
        geometryVertexBuffer = 0
        framebuffer = 0
        colorTexture = 0
        depthBuffer = 0
        shadowFramebuffer = 0
        shadowTexture = 0
        shadowDepthBuffer = 0
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
            error("OpenGL program link failed: " + message)
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
            error("OpenGL shader compile failed: " + message)
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

    private fun generatedRenderbuffer(): Int {
        val ids = IntArray(1)
        GLES30.glGenRenderbuffers(1, ids, 0)
        return ids[0]
    }

    private fun generatedFramebuffer(): Int {
        val ids = IntArray(1)
        GLES30.glGenFramebuffers(1, ids, 0)
        return ids[0]
    }

    private fun uniformMatrix(program: Int, name: String, value: FloatArray) {
        GLES30.glUniformMatrix4fv(
            GLES30.glGetUniformLocation(program, name),
            1,
            false,
            value,
            0,
        )
    }

    private fun uniform3(program: Int, name: String, value: FloatArray) {
        GLES30.glUniform3f(
            GLES30.glGetUniformLocation(program, name),
            value[0],
            value[1],
            value[2],
        )
    }

    private fun checkGl(operation: String) {
        val error = GLES30.glGetError()
        check(error == GLES30.GL_NO_ERROR) {
            operation + " failed with OpenGL error 0x" + error.toString(16)
        }
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
        const val WATER_WIDTH = 64f
        const val WATER_DEPTH = 46f
        const val TRACK_RADIUS_X = 15.5f
        const val TRACK_RADIUS_Z = 9.5f
        const val TRACK_SPEED = 0.23f
        const val SHADOW_MAP_SIZE = 1024
        const val SPLASH_PARTICLE_COUNT = 384
        val SCENE_GROUP_COUNTS = intArrayOf(48, 18, 8, 12)

        const val SHADOW_GLSL = """
float unpackDepth(vec4 packedDepth) {
    return dot(packedDepth, vec4(
        1.0 / 16777216.0,
        1.0 / 65536.0,
        1.0 / 256.0,
        1.0
    ));
}

float sampleShadow(
    sampler2D shadowMap,
    vec2 shadowTexel,
    vec4 lightSpacePosition,
    float bias
) {
    vec3 projected = lightSpacePosition.xyz / lightSpacePosition.w;
    projected = projected * 0.5 + 0.5;
    if (projected.z <= 0.0 || projected.z >= 1.0 ||
        projected.x <= 0.0 || projected.x >= 1.0 ||
        projected.y <= 0.0 || projected.y >= 1.0) {
        return 0.0;
    }
    float shadow = 0.0;
    for (int x = -1; x <= 1; ++x) {
        for (int y = -1; y <= 1; ++y) {
            float closest = unpackDepth(texture(
                shadowMap,
                projected.xy + vec2(float(x), float(y)) * shadowTexel
            ));
            shadow += projected.z - bias > closest ? 1.0 : 0.0;
        }
    }
    return shadow / 9.0;
}
"""

        val WATER_FRAGMENT_SHADER = """#version 300 es
precision highp float;
in highp vec3 vWorldPosition;
in highp vec3 vNormal;
in highp vec4 vShadowPosition;
uniform float uTime;
uniform vec3 uCamera;
uniform vec3 uLightDirection;
uniform vec3 uRacerPosition;
uniform sampler2D uShadowMap;
uniform vec2 uShadowTexel;
out vec4 outputColor;
$SHADOW_GLSL

void main() {
    vec3 normal = vNormal;
    float detail = 0.0;
    for (int i = 0; i < 16; ++i) {
        float fi = float(i);
        float phase = vWorldPosition.x * (0.21 + fi * 0.031)
            + vWorldPosition.z * (0.17 + fi * 0.026)
            + uTime * (0.42 + fi * 0.019);
        detail += sin(phase) * cos(phase * 1.41 + fi * 0.73) * 0.014;
    }
    normal = normalize(normal + vec3(detail, 0.0, -detail * 0.82));
    vec3 viewDirection = normalize(uCamera - vWorldPosition);
    vec3 halfDirection = normalize(uLightDirection + viewDirection);
    float diffuse = max(dot(normal, uLightDirection), 0.0);
    float specular = pow(max(dot(normal, halfDirection), 0.0), 88.0);
    float fresnel = pow(1.0 - max(dot(normal, viewDirection), 0.0), 3.0);
    float crestFoam = smoothstep(0.37, 0.59, abs(vWorldPosition.y));
    float trackDistance = abs(length(vec2(
        vWorldPosition.x / 15.5,
        vWorldPosition.z / 9.5
    )) - 1.0);
    float laneFoam = (1.0 - smoothstep(0.10, 0.18, trackDistance)) * 0.10;
    float wakeDistance = length(vWorldPosition.xz - uRacerPosition.xz);
    float wakeFoam = (1.0 - smoothstep(0.5, 3.8, wakeDistance)) *
        (0.55 + 0.45 * sin(wakeDistance * 8.0 - uTime * 7.0));
    float shadow = sampleShadow(uShadowMap, uShadowTexel, vShadowPosition, 0.0035);
    float illumination = diffuse * mix(1.0, 0.42, shadow);
    vec3 deep = vec3(0.004, 0.055, 0.17);
    vec3 shallow = vec3(0.01, 0.45, 0.61);
    vec3 color = mix(deep, shallow, illumination * 0.72 + 0.17);
    color += vec3(0.30, 0.68, 0.96) * fresnel * 0.62;
    color += vec3(1.0, 0.88, 0.60) * specular * (1.0 - shadow) * 1.5;
    color += vec3(0.62, 0.88, 0.96) *
        (crestFoam * 0.16 + laneFoam + wakeFoam * 0.24);
    outputColor = vec4(color, 1.0);
}
"""

        const val WATER_VERTEX_SHADER = """#version 300 es
layout(location = 0) in vec2 aPosition;
uniform mat4 uViewProjection;
uniform mat4 uLightViewProjection;
uniform float uTime;
out highp vec3 vWorldPosition;
out highp vec3 vNormal;
out highp vec4 vShadowPosition;

void main() {
    float waveA = sin(aPosition.x * 0.69 + uTime * 1.43) * 0.29;
    float waveB = cos(aPosition.y * 0.87 - uTime * 1.09) * 0.22;
    float waveC = sin((aPosition.x + aPosition.y) * 0.43 + uTime * 0.81) * 0.16;
    float waveD = cos((aPosition.x - aPosition.y) * 1.27 - uTime * 1.72) * 0.07;
    float height = waveA + waveB + waveC + waveD;
    float dx = cos(aPosition.x * 0.69 + uTime * 1.43) * 0.2001
        + cos((aPosition.x + aPosition.y) * 0.43 + uTime * 0.81) * 0.0688
        - sin((aPosition.x - aPosition.y) * 1.27 - uTime * 1.72) * 0.0889;
    float dz = -sin(aPosition.y * 0.87 - uTime * 1.09) * 0.1914
        + cos((aPosition.x + aPosition.y) * 0.43 + uTime * 0.81) * 0.0688
        + sin((aPosition.x - aPosition.y) * 1.27 - uTime * 1.72) * 0.0889;
    vWorldPosition = vec3(aPosition.x, height, aPosition.y);
    vNormal = normalize(vec3(-dx, 1.0, -dz));
    vShadowPosition = uLightViewProjection * vec4(vWorldPosition, 1.0);
    gl_Position = uViewProjection * vec4(vWorldPosition, 1.0);
}
"""

        const val SCENE_VERTEX_SHADER = """#version 300 es
layout(location = 0) in vec3 aPosition;
layout(location = 1) in vec3 aNormal;
uniform mat4 uViewProjection;
uniform mat4 uLightViewProjection;
uniform float uTime;
uniform int uGroup;
out highp vec3 vNormal;
out highp vec3 vColor;
out highp vec4 vShadowPosition;

const float TAU = 6.28318530718;

vec2 orient(vec2 local, vec2 forward) {
    vec2 right = vec2(forward.y, -forward.x);
    return right * local.x + forward * local.y;
}

vec2 trackPosition(float angle) {
    return vec2(cos(angle) * 15.5, sin(angle) * 9.5);
}

vec2 trackForward(float angle) {
    return normalize(vec2(-sin(angle) * 15.5, cos(angle) * 9.5));
}

void main() {
    int id = gl_InstanceID;
    vec3 scale = vec3(1.0);
    vec3 localOffset = vec3(0.0);
    vec3 center = vec3(0.0);
    vec2 forward = vec2(0.0, 1.0);
    vec3 color = vec3(0.8);

    if (uGroup == 0) {
        int side = id / 24;
        int index = id - side * 24;
        float angle = float(index) / 24.0 * TAU;
        vec2 radii = side == 0 ? vec2(12.1, 6.2) : vec2(19.0, 13.0);
        center = vec3(cos(angle) * radii.x, 0.58, sin(angle) * radii.y);
        center.y += sin(uTime * 1.8 + float(id) * 0.71) * 0.12;
        scale = vec3(0.24, 0.72, 0.24);
        forward = trackForward(angle);
        color = side == 0 ? vec3(1.0, 0.22, 0.04) : vec3(1.0, 0.76, 0.08);
    } else if (uGroup == 1) {
        float angle = float(id) / 18.0 * TAU + 0.26;
        forward = trackForward(angle);
        vec2 right = vec2(forward.y, -forward.x);
        float lane = float(id % 3 - 1) * 1.55;
        vec2 track = trackPosition(angle) + right * lane;
        center = vec3(track.x, 0.50, track.y);
        center.y += sin(uTime * 1.2 + float(id)) * 0.08;
        scale = vec3(
            0.42 + float(id % 4) * 0.10,
            0.70 + float(id % 3) * 0.22,
            0.42 + float((id + 2) % 4) * 0.09
        );
        color = mix(vec3(0.95, 0.12, 0.025), vec3(1.0, 0.58, 0.04), float(id % 5) / 4.0);
    } else if (uGroup == 2) {
        float angle = uTime * 0.23;
        forward = trackForward(angle);
        vec2 track = trackPosition(angle);
        center = vec3(track.x, 0.0, track.y);
        if (id == 0) {
            scale = vec3(1.12, 0.28, 2.05);
            localOffset = vec3(0.0, 0.63, 0.0);
            color = vec3(0.96, 0.07, 0.025);
        } else if (id == 1) {
            scale = vec3(0.72, 0.22, 1.02);
            localOffset = vec3(0.0, 1.03, -0.18);
            color = vec3(0.08, 0.10, 0.14);
        } else if (id == 2) {
            scale = vec3(0.56, 0.20, 0.72);
            localOffset = vec3(0.0, 0.78, 1.55);
            color = vec3(1.0, 0.42, 0.03);
        } else if (id == 3) {
            scale = vec3(0.46, 0.52, 0.56);
            localOffset = vec3(0.0, 1.02, -0.98);
            color = vec3(0.15, 0.17, 0.20);
        } else if (id == 4 || id == 5) {
            scale = vec3(0.25, 0.16, 1.34);
            localOffset = vec3(id == 4 ? -1.05 : 1.05, 0.40, -0.18);
            color = vec3(1.0, 0.68, 0.04);
        } else if (id == 6) {
            scale = vec3(0.29, 0.66, 0.29);
            localOffset = vec3(0.0, 1.62, -0.34);
            color = vec3(0.08, 0.25, 0.91);
        } else {
            scale = vec3(0.11, 0.82, 0.11);
            localOffset = vec3(0.0, 1.52, -1.10);
            color = vec3(0.92, 0.95, 1.0);
        }
    } else {
        int gate = id / 4;
        int component = id - gate * 4;
        float angle = float(gate) / 3.0 * TAU + 0.52;
        forward = trackForward(angle);
        vec2 track = trackPosition(angle);
        center = vec3(track.x, 0.0, track.y);
        color = gate == 0 ? vec3(0.10, 0.82, 1.0) :
            (gate == 1 ? vec3(0.76, 0.18, 1.0) : vec3(0.18, 1.0, 0.56));
        if (component == 0 || component == 1) {
            scale = vec3(0.24, 1.65, 0.24);
            localOffset = vec3(component == 0 ? -3.15 : 3.15, 1.65, 0.0);
        } else if (component == 2) {
            scale = vec3(3.38, 0.20, 0.24);
            localOffset = vec3(0.0, 3.28, 0.0);
        } else {
            scale = vec3(0.82, 0.42, 0.14);
            localOffset = vec3(0.0, 3.32, 0.18);
            color = vec3(1.0, 0.92, 0.28);
        }
    }

    vec3 local = aPosition * scale;
    vec2 orientedPosition = orient(local.xz, forward);
    vec2 orientedOffset = orient(localOffset.xz, forward);
    vec3 world = center + vec3(
        orientedPosition.x + orientedOffset.x,
        local.y + localOffset.y,
        orientedPosition.y + orientedOffset.y
    );
    vec2 orientedNormal = orient(aNormal.xz, forward);
    vNormal = normalize(vec3(orientedNormal.x, aNormal.y, orientedNormal.y));
    vColor = color;
    vShadowPosition = uLightViewProjection * vec4(world, 1.0);
    gl_Position = uViewProjection * vec4(world, 1.0);
}
"""

        val SCENE_FRAGMENT_SHADER = """#version 300 es
precision highp float;
in highp vec3 vNormal;
in highp vec3 vColor;
in highp vec4 vShadowPosition;
uniform vec3 uLightDirection;
uniform sampler2D uShadowMap;
uniform vec2 uShadowTexel;
uniform int uShadowPass;
out vec4 outputColor;
$SHADOW_GLSL

vec4 packDepth(float depth) {
    const vec4 shift = vec4(16777216.0, 65536.0, 256.0, 1.0);
    const vec4 mask = vec4(0.0, 1.0 / 256.0, 1.0 / 256.0, 1.0 / 256.0);
    vec4 packed = fract(depth * shift);
    packed -= packed.xxyz * mask;
    return packed;
}

void main() {
    if (uShadowPass != 0) {
        outputColor = packDepth(gl_FragCoord.z);
        return;
    }
    vec3 normal = normalize(vNormal);
    float diffuse = max(dot(normal, uLightDirection), 0.0);
    float shadow = sampleShadow(uShadowMap, uShadowTexel, vShadowPosition, 0.0045);
    float lighting = 0.22 + diffuse * mix(0.78, 0.28, shadow);
    float rim = pow(1.0 - abs(normal.y), 2.0) * 0.16;
    outputColor = vec4(vColor * lighting + rim, 1.0);
}
"""

        const val PARTICLE_VERTEX_SHADER = """#version 300 es
uniform mat4 uViewProjection;
uniform float uTime;
out highp float vAlpha;
out highp vec3 vColor;

float hash(float value) {
    return fract(sin(value * 91.3458) * 47453.5453);
}

void main() {
    float id = float(gl_VertexID);
    float age = fract(uTime * 0.74 + id / 384.0);
    float angle = uTime * 0.23 - age * 0.38;
    vec2 center = vec2(cos(angle) * 15.5, sin(angle) * 9.5);
    vec2 forward = normalize(vec2(-sin(angle) * 15.5, cos(angle) * 9.5));
    vec2 right = vec2(forward.y, -forward.x);
    float randomA = hash(id + 3.1) * 2.0 - 1.0;
    float randomB = hash(id + 17.7);
    vec2 position = center - forward * age * (2.2 + randomB * 2.4);
    position += right * randomA * (0.25 + age * 1.55);
    float height = 0.42 + sin(age * 3.14159265) * (1.2 + randomB * 1.4);
    height -= age * age * 0.65;
    gl_Position = uViewProjection * vec4(position.x, height, position.y, 1.0);
    gl_PointSize = mix(10.0, 2.2, age);
    vAlpha = (1.0 - age) * (0.48 + randomB * 0.50);
    vColor = mix(vec3(0.36, 0.78, 1.0), vec3(0.92, 0.98, 1.0), randomB);
}
"""

        const val PARTICLE_FRAGMENT_SHADER = """#version 300 es
precision mediump float;
in highp float vAlpha;
in highp vec3 vColor;
out vec4 outputColor;
void main() {
    vec2 point = gl_PointCoord * 2.0 - 1.0;
    float radius = dot(point, point);
    if (radius > 1.0) discard;
    outputColor = vec4(vColor, vAlpha * (1.0 - radius));
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
