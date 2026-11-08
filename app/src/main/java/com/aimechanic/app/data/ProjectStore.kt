package com.aimechanic.app.data

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Local-first persistence for one vehicle project.
 *
 * Layout under [rootDir]:
 *   project.json
 *   sessions/<sessionId>/session.json
 *
 * Pure java.io so the class is unit-testable on the JVM. Nothing here
 * touches the network; exports are explicit user actions.
 */
class ProjectStore(private val rootDir: File) {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val projectFile get() = File(rootDir, "project.json")
    private fun sessionDir(sessionId: String) = File(File(rootDir, "sessions"), sessionId)
    private fun sessionFile(sessionId: String) = File(sessionDir(sessionId), "session.json")

    fun loadProject(): ProjectManifest? {
        if (!projectFile.isFile) return null
        return runCatching {
            json.decodeFromString<ProjectManifest>(projectFile.readText())
        }.getOrNull()
    }

    /** Loads the existing project or creates the seed project (initial test vehicle). */
    fun loadOrCreateProject(nowEpochMs: Long): ProjectManifest {
        loadProject()?.let { return it }
        val created = ProjectManifest(
            projectId = "project-" + UUID.randomUUID().toString().take(8),
            name = "2022 Corolla XLE — engine bay",
            createdAtEpochMs = nowEpochMs,
            updatedAtEpochMs = nowEpochMs,
            vehicle = VehicleProfile.initialTestVehicle()
        )
        saveProject(created)
        return created
    }

    fun saveProject(manifest: ProjectManifest) {
        rootDir.mkdirs()
        projectFile.writeText(json.encodeToString(manifest))
    }

    fun saveSession(session: SessionRecord) {
        sessionDir(session.sessionId).mkdirs()
        sessionFile(session.sessionId).writeText(json.encodeToString(session))
        // Keep the manifest's session index + timestamp in sync.
        loadProject()?.let { manifest ->
            val ids = (manifest.sessionIds + session.sessionId).distinct()
            saveProject(
                manifest.copy(
                    sessionIds = ids,
                    updatedAtEpochMs = session.endedAtEpochMs ?: manifest.updatedAtEpochMs
                )
            )
        }
    }

    fun loadSession(sessionId: String): SessionRecord? {
        val f = sessionFile(sessionId)
        if (!f.isFile) return null
        return runCatching {
            json.decodeFromString<SessionRecord>(f.readText())
        }.getOrNull()
    }

    fun listSessions(): List<SessionRecord> {
        val manifest = loadProject() ?: return emptyList()
        return manifest.sessionIds.mapNotNull { loadSession(it) }
            .sortedByDescending { it.startedAtEpochMs }
    }

    fun deleteAllData() {
        rootDir.deleteRecursively()
    }

    /**
     * Exports the project as a ZIP in the documented layout:
     *   project.json, sessions/<id>/session.json, readme.txt
     * When [includeVin] is false the VIN is blanked in the exported copy
     * only; the on-device project is untouched.
     */
    fun exportZip(outFile: File, includeVin: Boolean): File {
        val manifest = loadProject() ?: error("No project to export")
        val exportedManifest =
            if (includeVin) manifest else manifest.copy(vehicle = manifest.vehicle.copy(vin = ""))
        outFile.parentFile?.mkdirs()
        ZipOutputStream(FileOutputStream(outFile)).use { zip ->
            zip.putNextEntry(ZipEntry("project.json"))
            zip.write(json.encodeToString(exportedManifest).toByteArray())
            zip.closeEntry()

            for (sessionId in manifest.sessionIds) {
                val f = sessionFile(sessionId)
                if (!f.isFile) continue
                zip.putNextEntry(ZipEntry("sessions/$sessionId/session.json"))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }

            zip.putNextEntry(ZipEntry("readme.txt"))
            zip.write(readmeText(exportedManifest).toByteArray())
            zip.closeEntry()
        }
        return outFile
    }

    private fun readmeText(manifest: ProjectManifest): String = buildString {
        appendLine("AI Mechanic — vehicle project export")
        appendLine("Project: ${manifest.name} (${manifest.projectId})")
        appendLine(
            "Vehicle: ${manifest.vehicle.year} ${manifest.vehicle.make} " +
                "${manifest.vehicle.model} ${manifest.vehicle.trim}, " +
                "${manifest.vehicle.engine}, ${manifest.vehicle.transmission}"
        )
        appendLine("Units: ${manifest.coordinateUnit}")
        appendLine()
        appendLine("Pin positions are stored in the ARCore world frame of their session.")
        appendLine("That frame does not persist across app restarts: reopened sessions")
        appendLine("are inspection data, not automatically re-aligned AR overlays.")
        appendLine("Provenance (observed / documented / inferred) is recorded per pin")
        appendLine("and per claim; inferred content is never merged into observed data.")
    }
}
