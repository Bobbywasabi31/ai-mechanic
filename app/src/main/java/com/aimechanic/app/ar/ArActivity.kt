package com.aimechanic.app.ar

import android.Manifest
import android.content.pm.PackageManager
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.MotionEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.aimechanic.app.data.AnnotationPin
import com.aimechanic.app.data.PinStatus
import com.aimechanic.app.data.ProjectStore
import com.aimechanic.app.data.SessionRecord
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Plane
import com.google.ar.core.Point
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.UnavailableException
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * Live AR scan screen (milestone M1):
 *  - ARCore camera view with tracking + depth status,
 *  - tap a real surface point to drop a world-anchored 3D arrow pin,
 *  - label / confirm / delete pins,
 *  - save the session (profile + pins) to local storage.
 *
 * Pins are anchored for the life of this AR session. Saved pins reopen
 * as data in the session viewer; they are not claimed to be physically
 * re-aligned on a later visit (that is a separate milestone).
 */
class ArActivity : ComponentActivity() {

    private var session: Session? = null
    private var installRequested = false
    private var depthEnabled = false
    private lateinit var renderer: ArRenderer
    private lateinit var glView: GLSurfaceView
    private lateinit var store: ProjectStore

    private val sessionId: String =
        "session-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
    private val sessionStartedAt = System.currentTimeMillis()

    // UI state, also read by the save path.
    private val uiPins = mutableStateListOf<UiPin>()
    private var arStatus by mutableStateOf("Starting AR…")
    private var trackingText by mutableStateOf("—")
    private var centerDepthText by mutableStateOf("—")
    private var projections by mutableStateOf<List<PinProjection>>(emptyList())
    private var selectedPinId by mutableStateOf<String?>(null)
    private var editingPinId by mutableStateOf<String?>(null)
    private var pinCounter = 0

    private data class UiPin(val pin: AnnotationPin, val placed: PlacedPin)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ProjectStore(File(filesDir, "project"))
        renderer = ArRenderer {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.rotation
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, "Camera permission is required for AR scan", Toast.LENGTH_LONG)
                .show()
            finish()
            return
        }

        setContent {
            MaterialTheme {
                ArScreen()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (session == null) {
            createSession()
        }
        try {
            session?.resume()
        } catch (e: Exception) {
            arStatus = "Could not resume AR: ${e.message}"
        }
        if (::glView.isInitialized) glView.onResume()
    }

    override fun onPause() {
        super.onPause()
        if (::glView.isInitialized) glView.onPause()
        session?.pause()
        saveSession(showToast = false)
    }

    override fun onDestroy() {
        session?.close()
        session = null
        super.onDestroy()
    }

    private fun createSession() {
        val availability = ArCoreApk.getInstance().checkAvailability(this)
        if (availability.isTransient) {
            arStatus = "Checking ARCore availability…"
            return
        }
        if (!availability.isSupported) {
            arStatus = "ARCore is not supported on this device."
            return
        }
        try {
            val installStatus =
                ArCoreApk.getInstance().requestInstall(this, !installRequested)
            if (installStatus == ArCoreApk.InstallStatus.INSTALL_REQUESTED) {
                installRequested = true
                arStatus = "Installing Play Services for AR…"
                return
            }
        } catch (e: UnavailableException) {
            arStatus = "ARCore install failed: ${e.message}"
            return
        }

        val newSession = try {
            Session(this)
        } catch (e: UnavailableException) {
            arStatus = "Could not create AR session: ${e.message}"
            return
        }
        val config = Config(newSession)
        depthEnabled = newSession.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
        config.depthMode =
            if (depthEnabled) Config.DepthMode.AUTOMATIC else Config.DepthMode.DISABLED
        config.focusMode = Config.FocusMode.AUTO
        config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
        newSession.configure(config)
        session = newSession
        renderer.session = newSession
        arStatus = if (depthEnabled) {
            "Depth API on. Move the phone slowly, then tap a part to place a pin."
        } else {
            "Depth API not available; pins use feature-point hit tests. Tap a part to place a pin."
        }
    }

    private fun handleTap(x: Float, y: Float) {
        val frame = renderer.latestFrame ?: return
        if (frame.camera.trackingState != TrackingState.TRACKING) {
            Toast.makeText(this, "Tracking not ready — move the phone slowly", Toast.LENGTH_SHORT)
                .show()
            return
        }
        val hits = frame.hitTest(x, y)
        val hit = hits.firstOrNull {
            it.trackable is Point || it.trackable is Plane
        } ?: run {
            Toast.makeText(
                this, "No surface found there — try a clearer, closer point", Toast.LENGTH_SHORT
            ).show()
            return
        }
        val anchor = hit.createAnchor()
        pinCounter += 1
        val id = "pin-%03d".format(pinCounter)
        val pose = hit.hitPose
        val placed = PlacedPin(id, anchor, PinStatus.USER_MARKED)
        val pin = AnnotationPin(
            id = id,
            label = "Pin $pinCounter",
            x = pose.tx(),
            y = pose.ty(),
            z = pose.tz(),
            status = PinStatus.USER_MARKED,
            depthAtPlacementMeters = hit.distance,
            createdAtEpochMs = System.currentTimeMillis()
        )
        renderer.queueAddPin(placed)
        uiPins.add(UiPin(pin, placed))
        selectedPinId = id
        editingPinId = id // prompt for a real label right away
    }

    private fun saveSession(showToast: Boolean) {
        if (!::store.isInitialized) return
        val project = store.loadOrCreateProject(System.currentTimeMillis())
        val record = SessionRecord(
            sessionId = sessionId,
            projectId = project.projectId,
            startedAtEpochMs = sessionStartedAt,
            endedAtEpochMs = System.currentTimeMillis(),
            depthEnabled = depthEnabled,
            trackingNotes = arStatus,
            pins = uiPins.map { it.pin }
        )
        store.saveSession(record)
        if (showToast) {
            Toast.makeText(this, "Session saved (${record.pins.size} pins)", Toast.LENGTH_SHORT)
                .show()
        }
    }

    @Composable
    private fun ArScreen() {
        // Poll renderer state for the HUD + label chips.
        LaunchedEffect(Unit) {
            while (true) {
                trackingText = renderer.trackingState.name
                centerDepthText = renderer.centerDepthMeters
                    ?.let { "%.2f m".format(it) } ?: "—"
                projections = renderer.pinProjections
                delay(150)
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                factory = { ctx ->
                    GLSurfaceView(ctx).also { view ->
                        glView = view
                        view.preserveEGLContextOnPause = true
                        view.setEGLContextClientVersion(3)
                        view.setRenderer(renderer)
                        view.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                        var downX = 0f
                        var downY = 0f
                        var downAt = 0L
                        view.setOnTouchListener { _, event ->
                            when (event.action) {
                                MotionEvent.ACTION_DOWN -> {
                                    downX = event.x; downY = event.y
                                    downAt = System.currentTimeMillis()
                                    true
                                }
                                MotionEvent.ACTION_UP -> {
                                    val quick = System.currentTimeMillis() - downAt < 300
                                    val still = abs(event.x - downX) < 24 &&
                                        abs(event.y - downY) < 24
                                    if (quick && still) handleTap(event.x, event.y)
                                    true
                                }
                                else -> true
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // Label chips, positioned from the renderer's projections.
            val density = LocalDensity.current
            for (proj in projections) {
                if (!proj.visible) continue
                val uiPin = uiPins.firstOrNull { it.pin.id == proj.id } ?: continue
                val chipColor = when (uiPin.pin.status) {
                    PinStatus.USER_MARKED -> Color(0xCCB26A00)
                    PinStatus.CONFIRMED -> Color(0xCC1E7D32)
                    PinStatus.AI_CANDIDATE -> Color(0xCC6A1B9A)
                }
                Box(
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                (proj.xPx - 30 * density.density).toInt(),
                                (proj.yPx - 120 * density.density).toInt()
                            )
                        }
                        .background(chipColor)
                        .clickable { selectedPinId = proj.id }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(uiPin.pin.label, color = Color.White, style = MaterialTheme.typography.labelMedium)
                }
            }

            // HUD (top).
            Surface(
                color = Color(0xAA000000),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        "Tracking: $trackingText · Depth center: $centerDepthText · " +
                            "Pins: ${uiPins.size}",
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge
                    )
                    Text(
                        arStatus,
                        color = Color(0xFFDDDDDD),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            // Bottom bar: selected-pin actions + save.
            Surface(
                color = Color(0xAA000000),
                modifier = Modifier
                    .align(androidx.compose.ui.Alignment.BottomCenter)
                    .fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp)
                ) {
                    val selected = uiPins.firstOrNull { it.pin.id == selectedPinId }
                    if (selected != null) {
                        Text(
                            "Selected: ${selected.pin.label} (${selected.pin.status.name})",
                            color = Color.White,
                            style = MaterialTheme.typography.labelLarge
                        )
                        Spacer(Modifier.height(6.dp))
                        Row {
                            Button(onClick = { editingPinId = selected.pin.id }) {
                                Text("Rename")
                            }
                            Spacer(Modifier.width(8.dp))
                            Button(onClick = {
                                val idx = uiPins.indexOf(selected)
                                val updated = selected.pin.copy(
                                    status = PinStatus.CONFIRMED,
                                    lastValidatedAtEpochMs = System.currentTimeMillis()
                                )
                                selected.placed.status = PinStatus.CONFIRMED
                                uiPins[idx] = selected.copy(pin = updated)
                            }) {
                                Text("Confirm")
                            }
                            Spacer(Modifier.width(8.dp))
                            Button(onClick = {
                                renderer.queueRemovePin(selected.pin.id)
                                uiPins.remove(selected)
                                selectedPinId = null
                            }) {
                                Text("Delete")
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    Row {
                        Button(onClick = { saveSession(showToast = true) }) {
                            Text("Save session")
                        }
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = { finish() }) {
                            Text("Done", color = Color.White)
                        }
                    }
                }
            }
        }

        // Rename dialog.
        val editing = uiPins.firstOrNull { it.pin.id == editingPinId }
        if (editing != null) {
            var text by remember(editing.pin.id) { mutableStateOf(editing.pin.label) }
            AlertDialog(
                onDismissRequest = { editingPinId = null },
                title = { Text("Pin label") },
                text = {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        label = { Text("Label") },
                        singleLine = true
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        val idx = uiPins.indexOf(editing)
                        uiPins[idx] = editing.copy(pin = editing.pin.copy(label = text.trim()
                            .ifEmpty { editing.pin.label }))
                        editingPinId = null
                    }) { Text("Save") }
                },
                dismissButton = {
                    TextButton(onClick = { editingPinId = null }) { Text("Cancel") }
                }
            )
        }
    }
}
