package com.aimechanic.app.ar

import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import com.aimechanic.app.data.PinStatus
import com.google.ar.core.Anchor
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.NotYetAvailableException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.sin

/** A pin placed in the AR world: an anchor plus display metadata. */
class PlacedPin(
    val id: String,
    val anchor: Anchor,
    @Volatile var status: PinStatus
)

/** Where a pin currently lands on screen (pixels), for label overlays. */
data class PinProjection(
    val id: String,
    val xPx: Float,
    val yPx: Float,
    val visible: Boolean
)

/**
 * Minimal OpenGL ES 3 renderer for the AR view:
 *  - draws the live camera feed as the background,
 *  - draws a small 3D arrow at each placed pin's anchor,
 *  - reads the Depth API center-pixel distance for the HUD,
 *  - projects pin positions to screen space for label chips.
 *
 * It deliberately does NOT build meshes or point clouds from depth;
 * that is a later milestone (see README feature matrix).
 */
class ArRenderer(
    private val displayRotationProvider: () -> Int
) : GLSurfaceView.Renderer {

    @Volatile
    var session: Session? = null
        set(value) {
            field = value
            attachTextureIfReady()
        }

    /** Latest frame from the GL thread; used by the UI thread for hit tests. */
    @Volatile
    var latestFrame: Frame? = null
        private set

    @Volatile
    var trackingState: TrackingState = TrackingState.STOPPED
        private set

    @Volatile
    var centerDepthMeters: Float? = null
        private set

    @Volatile
    var pinProjections: List<PinProjection> = emptyList()
        private set

    val pins = mutableListOf<PlacedPin>()
    private val pendingPins = ConcurrentLinkedQueue<PlacedPin>()
    private val pendingRemovals = ConcurrentLinkedQueue<String>()

    private var cameraTextureId = -1
    private var backgroundProgram = 0
    private var objectProgram = 0
    private var arrowVertexCount = 0
    private var arrowBuffer: FloatBuffer? = null
    private var quadCoords: FloatBuffer = floatBufferOf(
        floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
    )
    private var quadTexCoords: FloatBuffer = floatBufferOf(FloatArray(8))
    private var viewportWidth = 0
    private var viewportHeight = 0
    private var lastRotation = -1
    private var textureAttached = false

    fun queueAddPin(pin: PlacedPin) {
        pendingPins.add(pin)
    }

    fun queueRemovePin(id: String) {
        pendingRemovals.add(id)
    }

    private fun attachTextureIfReady() {
        val s = session ?: return
        if (cameraTextureId != -1 && !textureAttached) {
            s.setCameraTextureName(cameraTextureId)
            textureAttached = true
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0.05f, 0.05f, 0.08f, 1f)

        // Camera feed texture (external OES).
        val textures = IntArray(1)
        GLES30.glGenTextures(1, textures, 0)
        cameraTextureId = textures[0]
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)
        GLES30.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE
        )
        GLES30.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE
        )
        GLES30.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR
        )
        GLES30.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR
        )
        textureAttached = false
        attachTextureIfReady()

        backgroundProgram = buildProgram(BACKGROUND_VERTEX, BACKGROUND_FRAGMENT)
        objectProgram = buildProgram(OBJECT_VERTEX, OBJECT_FRAGMENT)

        val arrow = buildArrowMesh()
        arrowVertexCount = arrow.size / 3
        arrowBuffer = floatBufferOf(arrow)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        viewportWidth = width
        viewportHeight = height
        lastRotation = -1 // force display-geometry update on next frame
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        val s = session ?: return

        // Apply queued pin changes on the GL thread.
        while (true) {
            val pin = pendingPins.poll() ?: break
            pins.add(pin)
        }
        while (true) {
            val id = pendingRemovals.poll() ?: break
            pins.removeAll { pin ->
                if (pin.id == id) {
                    pin.anchor.detach()
                    true
                } else false
            }
        }

        val rotation = displayRotationProvider()
        if (rotation != lastRotation && viewportWidth > 0) {
            s.setDisplayGeometry(rotation, viewportWidth, viewportHeight)
            lastRotation = rotation
        }

        val frame = try {
            s.update()
        } catch (e: Exception) {
            return
        }
        latestFrame = frame
        val camera = frame.camera
        trackingState = camera.trackingState

        if (frame.hasDisplayGeometryChanged()) {
            frame.transformCoordinates2d(
                Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
                quadCoords,
                Coordinates2d.TEXTURE_NORMALIZED,
                quadTexCoords
            )
        }

        drawBackground(frame)
        updateCenterDepth(frame)

        if (camera.trackingState == TrackingState.TRACKING) {
            drawPins(frame)
        } else {
            pinProjections = emptyList()
        }
    }

    private fun drawBackground(frame: Frame) {
        // Draw the camera image behind everything, depth test off.
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthMask(false)
        GLES30.glUseProgram(backgroundProgram)
        val posLoc = GLES30.glGetAttribLocation(backgroundProgram, "a_Position")
        val texLoc = GLES30.glGetAttribLocation(backgroundProgram, "a_TexCoord")
        val texUniform = GLES30.glGetUniformLocation(backgroundProgram, "u_Texture")
        quadCoords.position(0)
        quadTexCoords.position(0)
        GLES30.glVertexAttribPointer(posLoc, 2, GLES30.GL_FLOAT, false, 0, quadCoords)
        GLES30.glVertexAttribPointer(texLoc, 2, GLES30.GL_FLOAT, false, 0, quadTexCoords)
        GLES30.glEnableVertexAttribArray(posLoc)
        GLES30.glEnableVertexAttribArray(texLoc)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)
        GLES30.glUniform1i(texUniform, 0)
        // The camera texture timestamp is set by session.update(); just draw.
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glDisableVertexAttribArray(posLoc)
        GLES30.glDisableVertexAttribArray(texLoc)
        GLES30.glDepthMask(true)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        @Suppress("UNUSED_VARIABLE") val unused = frame
    }

    private fun drawPins(frame: Frame) {
        val camera = frame.camera
        val view = FloatArray(16)
        val proj = FloatArray(16)
        camera.getViewMatrix(view, 0)
        camera.getProjectionMatrix(proj, 0, 0.01f, 30f)

        GLES30.glUseProgram(objectProgram)
        val posLoc = GLES30.glGetAttribLocation(objectProgram, "a_Position")
        val mvpLoc = GLES30.glGetUniformLocation(objectProgram, "u_MVP")
        val colorLoc = GLES30.glGetUniformLocation(objectProgram, "u_Color")
        val buffer = arrowBuffer ?: return
        buffer.position(0)
        GLES30.glVertexAttribPointer(posLoc, 3, GLES30.GL_FLOAT, false, 0, buffer)
        GLES30.glEnableVertexAttribArray(posLoc)

        val model = FloatArray(16)
        val modelView = FloatArray(16)
        val mvp = FloatArray(16)
        val projections = ArrayList<PinProjection>(pins.size)

        for (pin in pins) {
            if (pin.anchor.trackingState != TrackingState.TRACKING) {
                projections.add(PinProjection(pin.id, 0f, 0f, false))
                continue
            }
            pin.anchor.pose.toMatrix(model, 0)
            Matrix.multiplyMM(modelView, 0, view, 0, model, 0)
            Matrix.multiplyMM(mvp, 0, proj, 0, modelView, 0)
            GLES30.glUniformMatrix4fv(mvpLoc, 1, false, mvp, 0)
            val (r, g, b) = when (pin.status) {
                PinStatus.USER_MARKED -> Triple(1.0f, 0.72f, 0.15f)
                PinStatus.CONFIRMED -> Triple(0.25f, 0.9f, 0.4f)
                PinStatus.AI_CANDIDATE -> Triple(0.75f, 0.5f, 1.0f)
            }
            GLES30.glUniform4f(colorLoc, r, g, b, 1f)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, arrowVertexCount)

            // Project the anchor point to screen space for the label chip.
            val p = pin.anchor.pose
            val world = floatArrayOf(p.tx(), p.ty(), p.tz(), 1f)
            val clip = FloatArray(4)
            Matrix.multiplyMV(clip, 0, mvp, 0, world, 0)
            val w = clip[3]
            if (w > 0f) {
                val ndcX = clip[0] / w
                val ndcY = clip[1] / w
                val ndcZ = clip[2] / w
                val sx = (ndcX * 0.5f + 0.5f) * viewportWidth
                val sy = (0.5f - ndcY * 0.5f) * viewportHeight
                val visible = ndcZ in -1f..1f && sx in -100f..(viewportWidth + 100f) &&
                    sy in -100f..(viewportHeight + 100f)
                projections.add(PinProjection(pin.id, sx, sy, visible))
            } else {
                projections.add(PinProjection(pin.id, 0f, 0f, false))
            }
        }
        pinProjections = projections
        GLES30.glDisableVertexAttribArray(posLoc)
    }

    private fun updateCenterDepth(frame: Frame) {
        centerDepthMeters = try {
            val image = frame.acquireDepthImage16Bits()
            try {
                val plane = image.planes[0]
                val buffer = plane.buffer.asShortBuffer()
                val shortsPerRow = plane.rowStride / 2
                val cx = image.width / 2
                val cy = image.height / 2
                val mm = buffer.get(cy * shortsPerRow + cx).toInt() and 0xFFFF
                if (mm > 0) mm / 1000f else null
            } finally {
                image.close()
            }
        } catch (e: NotYetAvailableException) {
            null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Arrow mesh: tip at the origin pointing down at the anchored point,
     * a cone above it, a short shaft, and a flat ring marking the spot.
     * Non-indexed triangles, positions only.
     */
    private fun buildArrowMesh(): FloatArray {
        val verts = ArrayList<Float>()
        fun tri(
            ax: Float, ay: Float, az: Float,
            bx: Float, by: Float, bz: Float,
            cx: Float, cy: Float, cz: Float
        ) {
            verts.add(ax); verts.add(ay); verts.add(az)
            verts.add(bx); verts.add(by); verts.add(bz)
            verts.add(cx); verts.add(cy); verts.add(cz)
        }

        val segments = 20
        val coneBaseY = 0.055f
        val coneR = 0.024f
        val shaftTopY = 0.15f
        val shaftR = 0.0075f

        for (i in 0 until segments) {
            val a0 = (2 * Math.PI * i / segments).toFloat()
            val a1 = (2 * Math.PI * (i + 1) / segments).toFloat()
            val x0 = coneR * cos(a0); val z0 = coneR * sin(a0)
            val x1 = coneR * cos(a1); val z1 = coneR * sin(a1)
            // Cone side: apex at origin.
            tri(0f, 0f, 0f, x1, coneBaseY, z1, x0, coneBaseY, z0)
            // Cone cap.
            tri(0f, coneBaseY, 0f, x0, coneBaseY, z0, x1, coneBaseY, z1)
            // Shaft side.
            val sx0 = shaftR * cos(a0); val sz0 = shaftR * sin(a0)
            val sx1 = shaftR * cos(a1); val sz1 = shaftR * sin(a1)
            tri(sx0, coneBaseY, sz0, sx1, coneBaseY, sz1, sx1, shaftTopY, sz1)
            tri(sx0, coneBaseY, sz0, sx1, shaftTopY, sz1, sx0, shaftTopY, sz0)
            // Ground ring (annulus) at the anchored point.
            val ro = 0.036f; val ri = 0.026f; val ry = 0.002f
            val ox0 = ro * cos(a0); val oz0 = ro * sin(a0)
            val ox1 = ro * cos(a1); val oz1 = ro * sin(a1)
            val ix0 = ri * cos(a0); val iz0 = ri * sin(a0)
            val ix1 = ri * cos(a1); val iz1 = ri * sin(a1)
            tri(ox0, ry, oz0, ox1, ry, oz1, ix1, ry, iz1)
            tri(ox0, ry, oz0, ix1, ry, iz1, ix0, ry, iz0)
        }
        return verts.toFloatArray()
    }

    private fun buildProgram(vertexSource: String, fragmentSource: String): Int {
        val vertex = compileShader(GLES30.GL_VERTEX_SHADER, vertexSource)
        val fragment = compileShader(GLES30.GL_FRAGMENT_SHADER, fragmentSource)
        val program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, vertex)
        GLES30.glAttachShader(program, fragment)
        GLES30.glLinkProgram(program)
        return program
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        return shader
    }

    private fun floatBufferOf(data: FloatArray): FloatBuffer {
        val buffer = ByteBuffer.allocateDirect(data.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        buffer.put(data)
        buffer.position(0)
        return buffer
    }

    companion object {
        private const val BACKGROUND_VERTEX = """
            attribute vec4 a_Position;
            attribute vec2 a_TexCoord;
            varying vec2 v_TexCoord;
            void main() {
                gl_Position = a_Position;
                v_TexCoord = a_TexCoord;
            }
        """
        private const val BACKGROUND_FRAGMENT = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 v_TexCoord;
            uniform samplerExternalOES u_Texture;
            void main() {
                gl_FragColor = texture2D(u_Texture, v_TexCoord);
            }
        """
        private const val OBJECT_VERTEX = """
            attribute vec3 a_Position;
            uniform mat4 u_MVP;
            void main() {
                gl_Position = u_MVP * vec4(a_Position, 1.0);
            }
        """
        private const val OBJECT_FRAGMENT = """
            precision mediump float;
            uniform vec4 u_Color;
            void main() {
                gl_FragColor = u_Color;
            }
        """
    }
}
