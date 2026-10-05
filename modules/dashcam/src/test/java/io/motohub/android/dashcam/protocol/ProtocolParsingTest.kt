// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.dashcam.protocol

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The answers below are the ones the LINGTUO S1 gave on 2026-10-03, unless marked otherwise. */
class ProtocolParsingTest {

    @Test
    fun `the unquoted answer to an unknown command still parses`() {
        assertEquals(98, Answers.json("{result: 98}")!!.getInt("result"))
    }

    @Test
    fun `xml values are case-insensitive, span lines and take the first occurrence`() {
        val xml = "<Function>\n<Cmd>3012</Cmd>\n<STATUS>0</STATUS>\n<String>NA51055\n_V1</String><Cmd>9</Cmd></Function>"
        assertEquals("3012", Answers.xmlValue(xml, "Cmd"))
        assertEquals("0", Answers.xmlValue(xml, "Status"))
        assertEquals("NA51055\n_V1", Answers.xmlValue(xml, "String"))
        assertNull(Answers.xmlValue(xml, "Value"))
    }

    @Test
    fun `xml blocks list every element in order`() {
        val xml = "<LIST><file><name>a</name></file><FILE><name>b</name></FILE></LIST>"
        assertEquals(listOf("<name>a</name>", "<name>b</name>"), Answers.xmlBlocks(xml, "file"))
        assertEquals(emptyList<String>(), Answers.xmlBlocks(xml, "folder"))
    }

    @Test
    fun `eeasy settings pair each list with its current value and leave recording out`() {
        val items = JSONObject(
            """{"result":0,"info":[{"name":"mic","items":["off","on"],"index":[0,1]},
               {"name":"rec_split_duration","items":["off","1MIN","3MIN","5MIN","10MIN"],"index":[0,1,2,3,4]},
               {"name":"encodec","items":["h.264","h.265"],"index":[0,1]},
               {"name":"rec","items":["off","on"],"index":[0,1]}]}"""
        )
        val values = JSONObject(
            """{"result":0,"info":[{"name":"mic","value":1},{"name":"rec_split_duration","value":3},
               {"name":"encodec","value":0},{"name":"rec","value":1}]}"""
        )
        val settings = EeasyProtocol.parseSettings(items, values)
        assertEquals(listOf("mic", "rec_split_duration", "encodec"), settings.map { it.key })
        assertEquals("Microphone", settings[0].label)
        assertEquals(listOf("Off", "On"), settings[0].options)
        assertEquals(1, settings[0].current)
        assertEquals("5MIN", settings[1].options[settings[1].current])
        assertEquals("H.264", settings[2].options[settings[2].current])
        assertEquals("1", settings[2].values[1])
    }

    @Test
    fun `eeasy status reads battery and card, and a card of size zero is not ready`() {
        val ready = EeasyProtocol.parseStatus(
            true,
            JSONObject("""{"capacity":99,"charge":0}"""),
            JSONObject("""{"status":0,"free":5730,"total":7592}""")
        )
        assertEquals(99, ready.batteryPercent)
        assertEquals(false, ready.charging)
        assertEquals(true, ready.cardReady)
        assertEquals(7592L, ready.cardTotalMb)

        val unformatted = EeasyProtocol.parseStatus(null, null, JSONObject("""{"status":1,"free":0,"total":0}"""))
        assertEquals(false, unformatted.cardReady)
        assertNull(unformatted.batteryPercent)
    }

    @Test
    fun `eeasy file list reads paths, times, sizes in KB and durations`() {
        // Shape from the camera's own app; the S1's card was empty when it was read.
        val json = JSONObject(
            """{"result":0,"info":[{"folder":"loop","count":2,"files":[
               {"name":"/mnt/card/video/20261003101500_0060.mp4","createtimestr":"20261003101500","size":102400,"duration":180,"type":1},
               {"name":"mnt/card/photo/IMG_0001.jpg","createtime":1790000000,"size":512}]}]}"""
        )
        val files = EeasyProtocol.parseFiles(json, "loop")
        assertEquals(2, files.size)
        assertEquals("/mnt/card/video/20261003101500_0060.mp4", files[0].path)
        assertEquals("20261003101500_0060.mp4", files[0].name)
        assertEquals(102400L * 1024, files[0].sizeBytes)
        assertEquals(180, files[0].durationSeconds)
        assertFalse(files[0].photo)
        assertEquals("/mnt/card/photo/IMG_0001.jpg", files[1].path)
        assertEquals(1790000000L * 1000, files[1].createdMillis)
        assertTrue(files[1].photo)
    }

    @Test
    fun `eeasy file list also accepts a single folder object`() {
        val json = JSONObject("""{"result":0,"info":{"folder":"event","files":[{"name":"/a/b.mp4","size":1}]}}""")
        assertEquals("event", EeasyProtocol.parseFiles(json, "loop").single().folder)
    }

    @Test
    fun `novatek file list turns card paths into HTTP paths`() {
        val xml = """<?xml version="1.0"?><LIST><ALLFile><File><NAME>2026_1003_101500_001.MP4</NAME>
            <FPATH>A:\CARDV\MOVIE\2026_1003_101500_001.MP4</FPATH><SIZE>104857600</SIZE><TIMECODE>1</TIMECODE>
            <TIME>2026/10/03 10:15:00</TIME><ATTR>32</ATTR></File></ALLFile></LIST>"""
        val file = NovatekProtocol.parseFiles(xml).single()
        assertEquals("/CARDV/MOVIE/2026_1003_101500_001.MP4", file.path)
        assertEquals("MOVIE", file.folder)
        assertEquals(104857600L, file.sizeBytes)
        assertTrue(file.createdMillis > 0)
    }

    @Test
    fun `mstar file list reads names, sizes and times`() {
        val xml = "<DCIM><file><name>/SD/Normal/F/FILE261003-101500F.MP4</name><format>avi</format>" +
            "<size>98765</size><attr>RW</attr><time>2026-10-03 10:15:00</time></file></DCIM>"
        val file = MstarProtocol.parseFiles(xml, "Normal").single()
        assertEquals("/SD/Normal/F/FILE261003-101500F.MP4", file.path)
        assertEquals(98765L, file.sizeBytes)
        assertTrue(file.createdMillis > 0)
    }

    @Test
    fun `hisilicon assignments read var lines`() {
        val values = Answers.assignments("var softversion=\"V1.0.2\";\nvar model=\"HX-1\";\n")
        assertEquals("V1.0.2", values["softversion"])
        assertEquals("HX-1", values["model"])
    }
}
