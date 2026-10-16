// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.immich

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import io.motohub.android.module.MotoHubModuleHost
import java.io.File

/**
 * Hands the open photo to Android's share sheet: the whole picture when Immich gives it, else the
 * preview, under the photo's own name. A video, or a phone where the file cannot be offered, gets
 * the Immich link instead. Null when the sheet opened, or why not.
 *
 * A module runs in the app's process and cannot declare a FileProvider of its own, so the file
 * goes where the app's provider already serves from: `cache/shared-diagnostics/`
 * (`res/xml/file_paths.xml`, root `shared_diagnostics`), the folder the app's own trip and log
 * exports are shared from. Nothing lands in the rider's gallery, and the cache is the system's to
 * clear. Before the sheet opens, the provider is asked for the file: an app whose provider no
 * longer serves that folder falls back to the link rather than sending a share that cannot open.
 *
 * Blocking: call it off the main thread.
 */
internal fun sharePhoto(host: MotoHubModuleHost, server: ImmichServer, id: String, d: PhotoDetails?): String? {
    val link = server.webUrl(id)
    if (d?.video != true) {
        val bytes = runCatching { server.image(id, ImmichServer.FULLSIZE) }.getOrNull()
            ?: runCatching { server.image(id, ImmichServer.PREVIEW) }.getOrNull()
        if (bytes != null) {
            val (ext, mime) = imageKind(bytes)
            val name = shareName(d?.fileName, id, ext)
            val uri = runCatching { offer(host, name, bytes) }
                .onFailure { host.log.log("immich: share as a file failed: ${it.message}") }
                .getOrNull()
            if (uri != null) {
                val send = Intent(Intent.ACTION_SEND).setType(mime)
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                send.clipData = ClipData.newRawUri(name, uri)
                return open(host, send)
            }
        } else if (link == null) {
            return "Immich did not send the photo."
        }
    }
    link ?: return "Check the address in the module's settings."
    return open(host, Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link))
}

/** Writes the picture under the app's provider and returns its content address, checked to open. */
private fun offer(host: MotoHubModuleHost, name: String, bytes: ByteArray): Uri {
    val context = host.context
    // Only the last share is kept: the folder holds nothing the rider has not just sent.
    val dir = File(context.cacheDir, "$SHARE_DIR/$SHARE_SUBDIR").apply { deleteRecursively(); mkdirs() }
    File(dir, name).writeBytes(bytes)
    val uri = Uri.Builder().scheme("content").authority(context.packageName + ".fileprovider")
        .appendPath(SHARE_ROOT).appendPath(SHARE_SUBDIR).appendPath(name).build()
    context.contentResolver.openFileDescriptor(uri, "r")?.close() ?: error("The provider gave nothing.")
    return uri
}

private fun open(host: MotoHubModuleHost, send: Intent): String? = try {
    host.context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    null
} catch (e: Exception) {
    "This phone has nothing to share with."
}

/** The file extension and type of an image from its first bytes: Immich sends JPEG or WebP, an original may be anything. */
internal fun imageKind(bytes: ByteArray): Pair<String, String> {
    fun at(offset: Int, text: String) =
        bytes.size >= offset + text.length && String(bytes, offset, text.length, Charsets.ISO_8859_1) == text
    return when {
        bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte() -> "jpg" to "image/jpeg"
        bytes.size >= 4 && bytes[0] == 0x89.toByte() && at(1, "PNG") -> "png" to "image/png"
        at(0, "RIFF") && at(8, "WEBP") -> "webp" to "image/webp"
        at(0, "GIF8") -> "gif" to "image/gif"
        at(4, "ftypavi") -> "avif" to "image/avif"
        at(4, "ftyp") && listOf("heic", "heix", "mif1", "msf1", "hevc").any { at(8, it) } -> "heic" to "image/heic"
        else -> "jpg" to "image/*"
    }
}

/** "IMG_1234.HEIC" shared as JPEG becomes "IMG_1234.jpg"; no name, or nothing left of it, "immich-<id>". */
internal fun shareName(fileName: String?, id: String, ext: String): String {
    val base = fileName?.substringBeforeLast('.')?.replace(Regex("[^A-Za-z0-9._ -]"), "_")?.trim('.', ' ')?.take(80)
    return (base?.takeIf { it.any(Char::isLetterOrDigit) } ?: "immich-${id.take(8)}") + ".$ext"
}

private const val SHARE_DIR = "shared-diagnostics"
private const val SHARE_ROOT = "shared_diagnostics"
private const val SHARE_SUBDIR = "immich"
