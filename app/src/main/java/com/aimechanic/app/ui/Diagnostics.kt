package com.aimechanic.app.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Session
import kotlinx.coroutines.delay

data class DiagnosticsResult(
    val device: String,
    val android: String,
    val cameraPermission: Boolean,
    val arCoreAvailability: String,
    val arCorePackageInstalled: Boolean,
    val depthSupported: String
)

/**
 * Runs the runtime capability checks the handoff asks for (M0): real
 * device model, camera permission, ARCore availability, and whether the
 * Depth API is actually supported on this handset. Depth support can
 * only be answered by creating a session, so that check runs once and
 * the session is closed immediately.
 */
fun runDiagnostics(context: Context): DiagnosticsResult {
    val availability = ArCoreApk.getInstance().checkAvailability(context)
    val arCorePkg = try {
        context.packageManager.getPackageInfo("com.google.ar.core", 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    val depthText = when {
        availability.isTransient -> "unknown yet (ARCore still checking)"
        !availability.isSupported -> "not applicable (ARCore unsupported)"
        else -> try {
            val session = Session(context)
            try {
                val supported = session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
                if (supported) "supported (AUTOMATIC)" else "not supported on this device"
            } finally {
                session.close()
            }
        } catch (e: Exception) {
            "check failed: ${e.message}"
        }
    }

    return DiagnosticsResult(
        device = "${Build.MANUFACTURER} ${Build.MODEL}",
        android = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        cameraPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED,
        arCoreAvailability = availability.name,
        arCorePackageInstalled = arCorePkg,
        depthSupported = depthText
    )
}

@Composable
fun DiagnosticsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var result by remember { mutableStateOf<DiagnosticsResult?>(null) }

    // Availability can be transient right after app start; retry a few times.
    LaunchedEffect(Unit) {
        repeat(5) {
            val r = runDiagnostics(context)
            result = r
            if (!r.arCoreAvailability.contains("UNKNOWN")) return@LaunchedEffect
            delay(400)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text("Diagnostics", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        val r = result
        if (r == null) {
            Text("Running checks…")
        } else {
            DiagRow("Device", r.device)
            DiagRow("System", r.android)
            DiagRow("Camera permission", if (r.cameraPermission) "granted" else "NOT granted")
            DiagRow("ARCore availability", r.arCoreAvailability)
            DiagRow(
                "Play Services for AR package",
                if (r.arCorePackageInstalled) "installed" else "not installed"
            )
            DiagRow("ARCore Depth API", r.depthSupported)
            Spacer(Modifier.height(12.dp))
            Text(
                "Depth support is what lets pins sit on real surfaces with a " +
                    "measured distance. Without it the app still places pins " +
                    "from camera feature points, and says so in the AR view.",
                style = MaterialTheme.typography.bodySmall
            )
        }
        Spacer(Modifier.height(20.dp))
        Button(onClick = onBack) { Text("Back") }
    }
}

@Composable
private fun DiagRow(label: String, value: String) {
    Text(label, style = MaterialTheme.typography.labelLarge)
    Text(value, style = MaterialTheme.typography.bodyLarge)
    Spacer(Modifier.height(10.dp))
}
