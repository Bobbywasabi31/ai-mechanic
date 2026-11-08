package com.aimechanic.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.aimechanic.app.ar.ArActivity
import com.aimechanic.app.data.AnnotationPin
import com.aimechanic.app.data.PinStatus
import com.aimechanic.app.data.ProjectStore
import com.aimechanic.app.data.SessionRecord
import com.aimechanic.app.data.VehicleProfile
import com.aimechanic.app.ui.DiagnosticsScreen
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class Screen { HOME, PROFILE, PINS, DIAGNOSTICS }

class MainActivity : ComponentActivity() {

    private lateinit var store: ProjectStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ProjectStore(File(filesDir, "project"))
        setContent {
            MaterialTheme {
                AppRoot(store)
            }
        }
    }
}

@Composable
private fun AppRoot(store: ProjectStore) {
    var screen by remember { mutableStateOf(Screen.HOME) }
    var refreshKey by remember { mutableIntStateOf(0) }
    val context = LocalContext.current

    when (screen) {
        Screen.HOME -> HomeScreen(
            store = store,
            refreshKey = refreshKey,
            onStartScan = {
                context.startActivity(Intent(context, ArActivity::class.java))
                refreshKey++
            },
            onEditProfile = { screen = Screen.PROFILE },
            onOpenPins = { screen = Screen.PINS },
            onOpenDiagnostics = { screen = Screen.DIAGNOSTICS },
            onDataChanged = { refreshKey++ }
        )
        Screen.PROFILE -> ProfileScreen(
            store = store,
            onBack = { screen = Screen.HOME; refreshKey++ }
        )
        Screen.PINS -> PinsScreen(
            store = store,
            onBack = { screen = Screen.HOME; refreshKey++ }
        )
        Screen.DIAGNOSTICS -> DiagnosticsScreen(onBack = { screen = Screen.HOME })
    }
}

@Composable
private fun HomeScreen(
    store: ProjectStore,
    refreshKey: Int,
    onStartScan: () -> Unit,
    onEditProfile: () -> Unit,
    onOpenPins: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onDataChanged: () -> Unit
) {
    val context = LocalContext.current
    val manifest = remember(refreshKey) { store.loadOrCreateProject(System.currentTimeMillis()) }
    val sessions = remember(refreshKey) { store.listSessions() }
    val totalPins = sessions.sumOf { it.pins.size }
    var includeVin by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) onStartScan()
        else Toast.makeText(context, "Camera permission is needed for AR scan", Toast.LENGTH_LONG)
            .show()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text("AI Mechanic", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "Phone-first, evidence-based vehicle notes. Pins you place are " +
                "observations; nothing here invents manual facts.",
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(16.dp))

        Text(manifest.name, style = MaterialTheme.typography.titleMedium)
        val v = manifest.vehicle
        Text(
            "${v.year} ${v.make} ${v.model} ${v.trim} · ${v.engine} · ${v.transmission}",
            style = MaterialTheme.typography.bodyLarge
        )
        Text(
            "Sessions: ${sessions.size} · Pins saved: $totalPins",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(16.dp))

        Button(
            onClick = {
                val granted = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.CAMERA
                ) == PackageManager.PERMISSION_GRANTED
                if (granted) onStartScan() else permissionLauncher.launch(Manifest.permission.CAMERA)
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Scan engine bay (AR)") }

        Spacer(Modifier.height(8.dp))
        Button(onClick = onEditProfile, modifier = Modifier.fillMaxWidth()) {
            Text("Vehicle profile")
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = onOpenPins, modifier = Modifier.fillMaxWidth()) {
            Text("Saved pins & sessions")
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = onOpenDiagnostics, modifier = Modifier.fillMaxWidth()) {
            Text("Diagnostics (ARCore / depth)")
        }

        Spacer(Modifier.height(20.dp))
        Text("Export", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = includeVin, onCheckedChange = { includeVin = it })
            Text("Include VIN in export")
        }
        Button(
            onClick = {
                val outDir = File(context.cacheDir, "exports").apply { mkdirs() }
                val out = File(outDir, "${manifest.projectId}.zip")
                runCatching { store.exportZip(out, includeVin) }
                    .onSuccess {
                        val uri = FileProvider.getUriForFile(
                            context, "${context.packageName}.fileprovider", out
                        )
                        val share = Intent(Intent.ACTION_SEND).apply {
                            type = "application/zip"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(share, "Export project"))
                    }
                    .onFailure {
                        Toast.makeText(context, "Export failed: ${it.message}", Toast.LENGTH_LONG)
                            .show()
                    }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Export project ZIP") }

        Spacer(Modifier.height(20.dp))
        TextButton(onClick = { confirmDelete = true }) {
            Text("Delete all local project data")
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete all local data?") },
            text = { Text("This removes the vehicle project, sessions and pins stored on this phone. There is no undo.") },
            confirmButton = {
                TextButton(onClick = {
                    store.deleteAllData()
                    confirmDelete = false
                    onDataChanged()
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun ProfileScreen(store: ProjectStore, onBack: () -> Unit) {
    val manifest = remember { store.loadOrCreateProject(System.currentTimeMillis()) }
    val context = LocalContext.current
    var year by remember { mutableStateOf(manifest.vehicle.year.toString()) }
    var make by remember { mutableStateOf(manifest.vehicle.make) }
    var model by remember { mutableStateOf(manifest.vehicle.model) }
    var trim by remember { mutableStateOf(manifest.vehicle.trim) }
    var engine by remember { mutableStateOf(manifest.vehicle.engine) }
    var transmission by remember { mutableStateOf(manifest.vehicle.transmission) }
    var region by remember { mutableStateOf(manifest.vehicle.region) }
    var vin by remember { mutableStateOf(manifest.vehicle.vin) }
    var modifications by remember { mutableStateOf(manifest.vehicle.modifications) }
    var notes by remember { mutableStateOf(manifest.vehicle.notes) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text("Vehicle profile", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        ProfileField("Year", year) { year = it }
        ProfileField("Make", make) { make = it }
        ProfileField("Model", model) { model = it }
        ProfileField("Trim", trim) { trim = it }
        ProfileField("Engine", engine) { engine = it }
        ProfileField("Transmission", transmission) { transmission = it }
        ProfileField("Region", region) { region = it }
        ProfileField("VIN (optional, stays on this device)", vin) { vin = it }
        ProfileField("Modifications", modifications) { modifications = it }
        ProfileField("Notes", notes) { notes = it }
        Spacer(Modifier.height(16.dp))
        Row {
            Button(onClick = {
                val updated = VehicleProfile(
                    year = year.toIntOrNull() ?: manifest.vehicle.year,
                    make = make, model = model, trim = trim,
                    engine = engine, transmission = transmission,
                    region = region, vin = vin,
                    modifications = modifications, notes = notes
                )
                store.saveProject(
                    manifest.copy(
                        vehicle = updated,
                        updatedAtEpochMs = System.currentTimeMillis()
                    )
                )
                Toast.makeText(context, "Profile saved", Toast.LENGTH_SHORT).show()
                onBack()
            }) { Text("Save") }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onBack) { Text("Cancel") }
        }
    }
}

@Composable
private fun ProfileField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun PinsScreen(store: ProjectStore, onBack: () -> Unit) {
    var refreshKey by remember { mutableIntStateOf(0) }
    val sessions = remember(refreshKey) { store.listSessions() }
    var renamingPin by remember { mutableStateOf<Pair<SessionRecord, AnnotationPin>?>(null) }
    val dateFmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US) }

    fun updatePin(session: SessionRecord, updated: AnnotationPin) {
        val pins = session.pins.map { if (it.id == updated.id) updated else it }
        store.saveSession(session.copy(pins = pins))
        refreshKey++
    }

    fun deletePin(session: SessionRecord, pin: AnnotationPin) {
        store.saveSession(session.copy(pins = session.pins.filterNot { it.id == pin.id }))
        refreshKey++
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text("Saved pins & sessions", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "Reopened pins are saved data from their session's coordinate " +
                "frame. The app does not claim they are re-aligned to the " +
                "car until an alignment step exists (planned).",
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(12.dp))

        if (sessions.isEmpty()) {
            Text("No saved sessions yet. Run an AR scan and place a pin.")
        }
        for (session in sessions) {
            Text(
                "${session.sessionId} · ${dateFmt.format(Date(session.startedAtEpochMs))} · " +
                    "depth ${if (session.depthEnabled) "on" else "off"}",
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(Modifier.height(4.dp))
            if (session.pins.isEmpty()) {
                Text("  (no pins)", style = MaterialTheme.typography.bodyMedium)
            }
            for (pin in session.pins) {
                Column(modifier = Modifier.padding(start = 8.dp, bottom = 8.dp)) {
                    Text(
                        "${pin.label} — ${pin.status.name.lowercase().replace('_', ' ')}",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        "pos (${"%.2f".format(pin.x)}, ${"%.2f".format(pin.y)}, " +
                            "${"%.2f".format(pin.z)}) m" +
                            (pin.depthAtPlacementMeters?.let { " · placed at ${"%.2f".format(it)} m" }
                                ?: ""),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row {
                        TextButton(onClick = { renamingPin = session to pin }) { Text("Rename") }
                        if (pin.status != PinStatus.CONFIRMED) {
                            TextButton(onClick = {
                                updatePin(
                                    session,
                                    pin.copy(
                                        status = PinStatus.CONFIRMED,
                                        lastValidatedAtEpochMs = System.currentTimeMillis()
                                    )
                                )
                            }) { Text("Confirm") }
                        }
                        TextButton(onClick = { deletePin(session, pin) }) { Text("Delete") }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        Button(onClick = onBack) { Text("Back") }
    }

    val renaming = renamingPin
    if (renaming != null) {
        val (session, pin) = renaming
        var text by remember(pin.id) { mutableStateOf(pin.label) }
        AlertDialog(
            onDismissRequest = { renamingPin = null },
            title = { Text("Rename pin") },
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
                    if (text.isNotBlank()) updatePin(session, pin.copy(label = text.trim()))
                    renamingPin = null
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { renamingPin = null }) { Text("Cancel") }
            }
        )
    }
}
