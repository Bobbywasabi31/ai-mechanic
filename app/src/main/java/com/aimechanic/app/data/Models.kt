package com.aimechanic.app.data

import kotlinx.serialization.Serializable

/**
 * Data model for a reusable vehicle project, following the handoff spec
 * (section 08). The core rule encoded here: every annotation and claim
 * carries a provenance status, and nothing in this layer ever upgrades a
 * guess into a measurement.
 */

enum class Provenance {
    /** Seen by the camera / placed by the user on a real surface. */
    OBSERVED,

    /** Stated by a user-supplied manufacturer document. */
    DOCUMENTED,

    /** Suggested by a model or by reasoning; not verified. */
    INFERRED
}

enum class PinStatus {
    /** Placed by the user, not yet double-checked. */
    USER_MARKED,

    /** User explicitly confirmed the identity/position. */
    CONFIRMED,

    /** Suggested by AI. Reserved for M4; the MVP never creates these. */
    AI_CANDIDATE
}

@Serializable
data class VehicleProfile(
    val year: Int,
    val make: String,
    val model: String,
    val trim: String,
    val engine: String,
    val transmission: String,
    val region: String = "",
    /** Optional and private: stays on-device, can be omitted from exports. */
    val vin: String = "",
    val modifications: String = "",
    val notes: String = ""
) {
    companion object {
        /** Seed profile for the initial test vehicle (handoff section 00). */
        fun initialTestVehicle() = VehicleProfile(
            year = 2022,
            make = "Toyota",
            model = "Corolla",
            trim = "XLE",
            engine = "1.8L",
            transmission = "CVT"
        )
    }
}

@Serializable
data class CaptureDevice(
    val manufacturer: String = "",
    val model: String = "",
    val androidVersion: String = "",
    val arCoreSupported: Boolean = false,
    val depthSupported: Boolean = false,
    val appVersion: String = ""
)

/** One 3D annotation, positioned in the local session frame (meters). */
@Serializable
data class AnnotationPin(
    val id: String,
    val label: String,
    val x: Float,
    val y: Float,
    val z: Float,
    val status: PinStatus = PinStatus.USER_MARKED,
    val provenance: Provenance = Provenance.OBSERVED,
    /** Depth (meters) read from the Depth API at placement time, if any. */
    val depthAtPlacementMeters: Float? = null,
    val createdAtEpochMs: Long = 0L,
    val lastValidatedAtEpochMs: Long? = null,
    val note: String = ""
)

/**
 * A stored AR session. Positions of pins are in this session's ARCore
 * world frame. That frame does NOT survive an app restart, so a reopened
 * session is data to inspect — the app must not claim the pins are
 * physically re-aligned until a real relocalization/alignment step runs
 * (planned, see README feature matrix).
 */
@Serializable
data class SessionRecord(
    val sessionId: String,
    val projectId: String,
    val startedAtEpochMs: Long,
    val endedAtEpochMs: Long? = null,
    val depthEnabled: Boolean = false,
    val trackingNotes: String = "",
    val pins: List<AnnotationPin> = emptyList()
)

/** A claim plus its evidence edges (handoff section 08). */
@Serializable
data class ClaimRecord(
    val id: String,
    val text: String,
    val kind: Provenance,
    val relatedPinId: String? = null,
    val evidenceSourceIds: List<String> = emptyList(),
    val caveats: List<String> = emptyList(),
    val reviewedByUser: Boolean = false
)

/** A user-supplied document reference. The MVP stores metadata only. */
@Serializable
data class SourceRecord(
    val sourceId: String,
    val title: String,
    val issuer: String = "",
    val pageNumber: Int? = null,
    val localPath: String = "",
    val rightsOrLicense: String = "user-provided"
)

@Serializable
data class ProjectManifest(
    val schemaVersion: Int = 1,
    val projectId: String,
    val name: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val vehicle: VehicleProfile,
    val captureDevice: CaptureDevice = CaptureDevice(),
    val coordinateUnit: String = "meters",
    val sessionIds: List<String> = emptyList(),
    val claims: List<ClaimRecord> = emptyList(),
    val sources: List<SourceRecord> = emptyList()
)
