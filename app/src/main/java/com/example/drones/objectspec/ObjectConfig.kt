package com.example.drones.objectspec

/**
 * Intrinsic dimensions of the target object, produced off-device by
 * tools/cad_dims/extract_dims.py from a CAD file. Always meters, Z-up.
 *
 * Site-specific values (table height, marker map) are NOT here — they are
 * measured on location and live separately.
 */
data class ObjectConfig(
    val objectId: String,
    val sourceFile: String,
    val sourceSha256: String,
    val unitsOriginal: String,   // "mm" | "cm" | "m" | "in" | "UNKNOWN"
    val heightM: Double,
    val widthM: Double,
    val depthM: Double,
    val upAxis: String,
    val schemaVersion: Int
) {
    /** Units could not be determined from the CAD file — app must prompt + confirm. */
    val unitsUnknown: Boolean get() = unitsOriginal.equals("UNKNOWN", ignoreCase = true)

    /** Largest horizontal footprint dimension — used to check it fits inside the inner perimeter. */
    val footprintMaxM: Double get() = maxOf(widthM, depthM)
}
