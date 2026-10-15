// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.immich

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailsTest {

    private val asset = JSONObject(
        """{"id":"a1b2c3d4-0001","type":"IMAGE","originalFileName":"IMG_1.HEIC","originalMimeType":"image/heic",
        "isFavorite":true,"visibility":"timeline","fileCreatedAt":"2026-08-15T09:12:00.000Z",
        "owner":{"name":"Vincenzo","email":"v@example.com"},
        "people":[{"name":"Anna"},{"name":""}],"tags":[{"value":"moto"}],
        "exifInfo":{"make":"Apple","model":"Apple iPhone 15 Pro","lensModel":null,"fNumber":1.8,"exposureTime":"1/250",
        "iso":80,"focalLength":6.86,"exifImageWidth":4032,"exifImageHeight":3024,"fileSizeInByte":2500000,
        "latitude":46.5,"longitude":10.4,"city":"Bormio","state":"Lombardia","country":"Italy","rating":0,
        "dateTimeOriginal":"2026-08-15T11:12:00.000+02:00","timeZone":"Europe/Rome"}}"""
    )

    @Test
    fun readsTheWholeRecord() {
        val d = PhotoDetails.parse(asset, listOf("Stelvio" to 42))
        assertEquals("IMG_1.HEIC", d.fileName)
        assertEquals("Apple iPhone 15 Pro", d.camera)
        assertNull(d.lens)
        assertEquals(1.8, d.fNumber!!, 0.0)
        assertEquals(80, d.iso)
        assertEquals("Bormio, Lombardia, Italy", d.place)
        assertTrue(d.located)
        assertTrue(d.favorite)
        assertNull(d.rating)
        assertEquals(listOf("Anna"), d.people)
        assertEquals(listOf("moto"), d.tags)
        assertEquals("Vincenzo", d.owner)
        assertEquals("2026-08-15T11:12:00.000+02:00", d.takenAt)
        assertFalse(d.video)
    }

    @Test
    fun noPositionAtZeroZero() {
        val a = JSONObject("""{"id":"x1234567","exifInfo":{"latitude":0,"longitude":0}}""")
        assertFalse(PhotoDetails.parse(a, emptyList()).located)
    }

    @Test
    fun albumsAndWords() {
        assertEquals(listOf("Stelvio" to 42, "Alpi" to null), PhotoDetails.albums("""[{"albumName":"Stelvio","assetCount":42},{"albumName":"Alpi"}]"""))
        assertEquals("1/250 s", Words.exposure("0.004"))
        assertEquals("1/250 s", Words.exposure("1/250"))
        assertEquals("0:12", Words.duration("00:00:12.345"))
        assertEquals("2.4 MB", Words.bytes(2_500_000))
    }
}
