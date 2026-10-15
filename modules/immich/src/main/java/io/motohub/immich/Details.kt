// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.immich

import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.time.OffsetDateTime
import java.util.Date
import java.util.Locale

/** Everything Immich says about one photo (`GET /api/assets/{id}`), as MOTO-HUB Studio reads it. Any field may be missing. */
class PhotoDetails(
    val id: String,
    val fileName: String?,
    val type: String?,
    val mimeType: String?,
    val originalPath: String?,
    val takenAt: String?,
    val timeZone: String?,
    val uploadedAt: String?,
    val fileModifiedAt: String?,
    val duration: String?,
    val favorite: Boolean,
    val archived: Boolean,
    val trashed: Boolean,
    val livePhoto: Boolean,
    val edited: Boolean,
    val visibility: String?,
    val width: Int?,
    val height: Int?,
    val fileSize: Long?,
    val make: String?,
    val model: String?,
    val lens: String?,
    val fNumber: Double?,
    val focalLength: Double?,
    val iso: Int?,
    val exposure: String?,
    val orientation: String?,
    val projection: String?,
    val rating: Int?,
    val description: String?,
    val latitude: Double?,
    val longitude: Double?,
    val city: String?,
    val state: String?,
    val country: String?,
    val owner: String?,
    val people: List<String>,
    val tags: List<String>,
    /** The albums that hold it: name and how many photos each has. */
    val albums: List<Pair<String, Int?>>
) {
    val video: Boolean get() = type == "VIDEO"
    val located: Boolean get() = latitude != null && longitude != null
    val camera: String? get() = listOfNotNull(make, model?.removePrefix(make.orEmpty())?.trim()?.ifBlank { null }).joinToString(" ").ifBlank { null }
    val place: String? get() = listOfNotNull(city, state, country).distinct().joinToString(", ").ifBlank { null }
    val panorama: Boolean get() = projection?.contains("EQUIRECT", ignoreCase = true) == true

    companion object {
        fun parse(a: JSONObject, albums: List<Pair<String, Int?>>): PhotoDetails {
            val exif = a.optJSONObject("exifInfo")
            val lat = exif.number("latitude")
            val lon = exif.number("longitude")
            val located = lat != null && lon != null && !(lat == 0.0 && lon == 0.0)
            return PhotoDetails(
                id = a.text("id").orEmpty(),
                fileName = a.text("originalFileName"),
                type = a.text("type"),
                mimeType = a.text("originalMimeType"),
                originalPath = a.text("originalPath"),
                takenAt = exif.text("dateTimeOriginal") ?: a.text("fileCreatedAt"),
                timeZone = exif.text("timeZone"),
                uploadedAt = a.text("createdAt"),
                fileModifiedAt = a.text("fileModifiedAt"),
                duration = a.text("duration")?.takeIf { it != "0:00:00.00000" && it != "0" },
                favorite = a.flag("isFavorite"),
                archived = a.flag("isArchived") || a.text("visibility") == "archive",
                trashed = a.flag("isTrashed"),
                livePhoto = a.text("livePhotoVideoId") != null,
                edited = a.flag("isEdited"),
                visibility = a.text("visibility"),
                width = (exif.number("exifImageWidth") ?: a.number("width"))?.toInt(),
                height = (exif.number("exifImageHeight") ?: a.number("height"))?.toInt(),
                fileSize = exif.number("fileSizeInByte")?.toLong(),
                make = exif.text("make"),
                model = exif.text("model"),
                lens = exif.text("lensModel"),
                fNumber = exif.number("fNumber"),
                focalLength = exif.number("focalLength"),
                iso = exif.number("iso")?.toInt(),
                exposure = exif.text("exposureTime") ?: exif.number("exposureTime")?.let(::trim),
                orientation = exif.text("orientation") ?: exif.number("orientation")?.toInt()?.toString(),
                projection = exif.text("projectionType"),
                rating = exif.number("rating")?.toInt()?.takeIf { it in 1..5 },
                description = exif.text("description"),
                latitude = lat.takeIf { located },
                longitude = lon.takeIf { located },
                city = exif.text("city"),
                state = exif.text("state"),
                country = exif.text("country"),
                owner = a.optJSONObject("owner")?.let { it.text("name") ?: it.text("email") },
                people = names(a.optJSONArray("people"), "name"),
                tags = names(a.optJSONArray("tags"), "value").ifEmpty { names(a.optJSONArray("tags"), "name") },
                albums = albums
            )
        }

        /** `GET /api/albums?assetId=` as names and counts. */
        fun albums(json: String): List<Pair<String, Int?>> {
            val array = JSONArray(json)
            return (0 until array.length()).mapNotNull { i ->
                val album = array.optJSONObject(i) ?: return@mapNotNull null
                (album.text("albumName") ?: return@mapNotNull null) to album.number("assetCount")?.toInt()
            }
        }

        private fun names(array: JSONArray?, field: String): List<String> =
            if (array == null) emptyList() else (0 until array.length()).mapNotNull { array.optJSONObject(it)?.text(field) }
    }
}

internal fun JSONObject?.text(key: String): String? {
    if (this == null || isNull(key)) return null
    val value = opt(key) as? String ?: return null
    return value.takeIf { it.isNotBlank() }
}

internal fun JSONObject?.number(key: String): Double? {
    if (this == null || isNull(key)) return null
    return when (val value = opt(key)) {
        is Number -> value.toDouble()
        is String -> value.toDoubleOrNull()
        else -> null
    }?.takeIf { it.isFinite() }
}

private fun JSONObject.flag(key: String): Boolean = optBoolean(key, false)

/** How a value reads on the photo's page. */
object Words {

    fun trim(value: Double): String = io.motohub.immich.trim(value)

    /** Immich's ISO instant in the phone's own words: "15 Oct 2026, 18:42". */
    fun moment(iso: String): String = runCatching {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(OffsetDateTime.parse(iso).toInstant().toEpochMilli()))
    }.getOrDefault(iso)

    /** "Wednesday 15 October 2026". */
    fun day(iso: String): String? = runCatching {
        DateFormat.getDateInstance(DateFormat.FULL).format(Date(OffsetDateTime.parse(iso).toInstant().toEpochMilli()))
    }.getOrNull()

    /** "18:42". */
    fun time(iso: String): String? = runCatching {
        DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(OffsetDateTime.parse(iso).toInstant().toEpochMilli()))
    }.getOrNull()

    /** "0.004" or "1/250" as "1/250 s"; long ones as seconds. */
    fun exposure(value: String): String {
        if ("/" in value) return "$value s"
        val seconds = value.toDoubleOrNull() ?: return value
        return if (seconds > 0 && seconds < 1) "1/${Math.round(1 / seconds)} s" else trim(seconds) + " s"
    }

    /** "00:00:12.345" or "12.3" as "0:12". */
    fun duration(value: String): String {
        val parts = value.split(':')
        val seconds = if (parts.size == 3) {
            (parts[0].toDoubleOrNull() ?: 0.0) * 3600 + (parts[1].toDoubleOrNull() ?: 0.0) * 60 + (parts[2].toDoubleOrNull() ?: 0.0)
        } else value.toDoubleOrNull()
        val total = seconds?.toLong() ?: return value
        return if (total >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", total / 3600, total / 60 % 60, total % 60)
        else String.format(Locale.ROOT, "%d:%02d", total / 60, total % 60)
    }

    fun bytes(value: Long): String = when {
        value >= 1L shl 30 -> String.format(Locale.ROOT, "%.1f GB", value / 1_073_741_824.0)
        value >= 1L shl 20 -> String.format(Locale.ROOT, "%.1f MB", value / 1_048_576.0)
        value >= 1L shl 10 -> String.format(Locale.ROOT, "%.0f KB", value / 1024.0)
        else -> "$value B"
    }

    /** EXIF orientation numbers as words. */
    fun orientation(value: String): String = when (value) {
        "1" -> "Normal"
        "3" -> "Upside down"
        "6" -> "Turned right"
        "8" -> "Turned left"
        "2", "4", "5", "7" -> "Mirrored ($value)"
        else -> value
    }
}

internal fun trim(value: Double): String =
    if (value % 1.0 == 0.0) value.toLong().toString() else String.format(Locale.ROOT, "%.1f", value)
