package com.aimechanic.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipFile

class ProjectStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var store: ProjectStore

    @Before
    fun setUp() {
        store = ProjectStore(File(tmp.root, "project"))
    }

    @Test
    fun seedProjectIsTheInitialTestVehicle() {
        val manifest = store.loadOrCreateProject(1_000L)
        assertEquals(2022, manifest.vehicle.year)
        assertEquals("Toyota", manifest.vehicle.make)
        assertEquals("Corolla", manifest.vehicle.model)
        assertEquals("XLE", manifest.vehicle.trim)
        assertEquals("1.8L", manifest.vehicle.engine)
        assertEquals("CVT", manifest.vehicle.transmission)
        assertEquals(1, manifest.schemaVersion)
    }

    @Test
    fun projectSurvivesReload() {
        val created = store.loadOrCreateProject(2_000L)
        val reloaded = ProjectStore(File(tmp.root, "project")).loadProject()
        assertEquals(created, reloaded)
    }

    @Test
    fun sessionAndPinsRoundTrip() {
        val manifest = store.loadOrCreateProject(3_000L)
        val pin = AnnotationPin(
            id = "pin-001",
            label = "Alternator candidate",
            x = 0.12f, y = -0.05f, z = -0.6f,
            depthAtPlacementMeters = 0.61f,
            createdAtEpochMs = 3_100L
        )
        val session = SessionRecord(
            sessionId = "session-test",
            projectId = manifest.projectId,
            startedAtEpochMs = 3_000L,
            endedAtEpochMs = 3_500L,
            depthEnabled = true,
            pins = listOf(pin)
        )
        store.saveSession(session)

        val sessions = store.listSessions()
        assertEquals(1, sessions.size)
        assertEquals(session, sessions[0])
        // Manifest session index updated.
        assertEquals(listOf("session-test"), store.loadProject()?.sessionIds)
    }

    @Test
    fun newPinsDefaultToUserMarkedObserved() {
        val pin = AnnotationPin(id = "p", label = "x", x = 0f, y = 0f, z = 0f)
        assertEquals(PinStatus.USER_MARKED, pin.status)
        assertEquals(Provenance.OBSERVED, pin.provenance)
    }

    @Test
    fun exportOmitsVinByDefault() {
        val manifest = store.loadOrCreateProject(4_000L)
        store.saveProject(
            manifest.copy(vehicle = manifest.vehicle.copy(vin = "SECRET-VIN-123"))
        )
        store.saveSession(
            SessionRecord(
                sessionId = "s1",
                projectId = manifest.projectId,
                startedAtEpochMs = 4_000L
            )
        )
        val zipFile = File(tmp.root, "export.zip")
        store.exportZip(zipFile, includeVin = false)

        ZipFile(zipFile).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toList()
            assertTrue(names.contains("project.json"))
            assertTrue(names.contains("sessions/s1/session.json"))
            assertTrue(names.contains("readme.txt"))
            val projectJson = zip.getInputStream(zip.getEntry("project.json"))
                .readBytes().decodeToString()
            assertFalse("VIN must be stripped from export", projectJson.contains("SECRET-VIN-123"))
        }
    }

    @Test
    fun exportIncludesVinWhenRequested() {
        val manifest = store.loadOrCreateProject(5_000L)
        store.saveProject(
            manifest.copy(vehicle = manifest.vehicle.copy(vin = "VIN-INCLUDED-9"))
        )
        val zipFile = File(tmp.root, "export2.zip")
        store.exportZip(zipFile, includeVin = true)
        ZipFile(zipFile).use { zip ->
            val projectJson = zip.getInputStream(zip.getEntry("project.json"))
                .readBytes().decodeToString()
            assertTrue(projectJson.contains("VIN-INCLUDED-9"))
        }
    }
}
