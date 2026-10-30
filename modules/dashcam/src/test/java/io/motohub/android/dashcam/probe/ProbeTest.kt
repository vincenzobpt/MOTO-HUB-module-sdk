// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.dashcam.probe

import io.motohub.android.module.ModuleDiagnosticReports
import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeTest {

    @Test
    fun `passwords, serials, positions and the device half of a mac never reach the log`() {
        val json = """{"model":"MT1","fw":"V1.2","wifi_password":"hunter22","sn":"A1B2C3","mac":"00:11:22:33:44:55","bssid":"001122334455","lat":45.4642}"""
        val out = Redact.text(json)
        assertTrue(out.contains("\"model\":\"MT1\""))
        assertTrue(out.contains("\"fw\":\"V1.2\""))
        assertFalse(out.contains("hunter22"))
        assertFalse(out.contains("A1B2C3"))
        assertFalse(out.contains("45.4642"))
        assertTrue(out.contains("\"mac\":\"00:11:22:**:**:**\""))
        assertTrue(out.contains("\"bssid\":\"001122******\""))

        val xml = Redact.text("<Cmd>3029</Cmd><SSID>VIOFO_MT1_3F2A1B</SSID><PASSPHRASE>12345678</PASSPHRASE><SerialNumber>XYZ</SerialNumber>")
        assertTrue(xml.contains("<Cmd>3029</Cmd>"))
        assertTrue(xml.contains("<SSID>VIOFO_MT1_******</SSID>"))
        assertFalse(xml.contains("12345678"))
        assertFalse(xml.contains("XYZ"))

        val text = Redact.text("var softversion=\"V1.0\"; var password=\"abc\"; user=admin&pwd=secret ap 00-11-22-33-44-55 at 45.123456, 9.123456 \$GPRMC,1,2,3")
        assertTrue(text.contains("softversion=\"V1.0\""))
        assertTrue(text.contains("password=\"***\""))
        assertTrue(text.contains("pwd=***"))
        assertTrue(text.contains("user=admin"))
        assertTrue(text.contains("00-11-22-**-**-**"))
        assertFalse(text.contains("45.123456"))
        assertFalse(text.contains("GPRMC"))
        assertEquals("password: '***'", Redact.text("password: 'x'"))
    }

    @Test
    fun `bodies are described in one line, and never read past their limit`() {
        val page = "<html><head><title> IPCAM </title></head>\n<body>Login</body></html>".toByteArray()
        assertTrue(CameraProbe.describe(page, "text/html").startsWith("title \"IPCAM\" | "))
        assertTrue(CameraProbe.describe(ByteArray(64) { it.toByte() }, "image/jpeg").startsWith("(64 bytes of binary"))
        assertEquals("(empty)", CameraProbe.describe(ByteArray(0), ""))
        assertEquals(100, CameraProbe.readSome(ByteArrayInputStream(ByteArray(10_000)), 100).size)

        val sdp = "v=0\r\ns=Session\r\nt=0 0\r\nm=video 0 RTP/AVP 96\r\na=rtpmap:96 H264/90000\r\na=control:track1\r\nb=AS:500\r\n"
        assertEquals("s=Session | m=video 0 RTP/AVP 96 | a=rtpmap:96 H264/90000 | a=control:track1", CameraProbe.sdpSummary(sdp))
    }

    @Test
    fun `the app's report is reached by name, and an older app has none`() {
        var asked = ""
        val host = object : ModuleDiagnosticReports {
            override val canSendReports = true
            override fun supportId() = "ABCD-1234"
            override fun sendReport(reason: String, done: (sent: Boolean) -> Unit) {
                asked = reason
                done(true)
            }
        }
        val reports = Reports.of(host)!!
        assertTrue(reports.canSend)
        assertEquals("ABCD-1234", reports.supportId())
        var sent: Boolean? = null
        reports.send("dashcam: test") { sent = it }
        assertEquals(true, sent)
        assertEquals("dashcam: test", asked)
        assertNull(Reports.of(Any()))
    }
}
