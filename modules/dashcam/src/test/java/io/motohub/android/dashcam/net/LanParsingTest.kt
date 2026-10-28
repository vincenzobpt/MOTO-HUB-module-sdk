// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.dashcam.net

import io.motohub.android.dashcam.LanSearch
import io.motohub.android.dashcam.Target
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Logins, ONVIF answers and addresses for cameras on the phone's own network. */
class LanParsingTest {

    @Test
    fun `digest answers the RFC 2617 example`() {
        val challenge = "Digest realm=\"testrealm@host.com\", qop=\"auth,auth-int\", " +
            "nonce=\"dcd98b7102dd2f0e8b11d0f600bfb0c093\", opaque=\"5ccc069c403ebaf9f0171e9517f40e41\""
        val header = Authenticator(Credentials("Mufasa", "Circle Of Life"), challenge, cnonce = "0a4f113b")
            .header("GET", "/dir/index.html")
        assertTrue(header, header.contains("response=\"6629fae49393a05397450978507c4ef1\""))
        assertTrue(header.contains("qop=auth, nc=00000001, cnonce=\"0a4f113b\""))
        assertTrue(header.contains("opaque=\"5ccc069c403ebaf9f0171e9517f40e41\""))
    }

    @Test
    fun `digest without qop and basic`() {
        // RFC 2069 form, as many RTSP servers still send it: response = MD5(HA1:nonce:HA2).
        val ha1 = Authenticator.hash("MD5", "admin:cam:secret")
        val ha2 = Authenticator.hash("MD5", "DESCRIBE:rtsp://10.0.0.5/live")
        val expected = Authenticator.hash("MD5", "$ha1:abc:$ha2")
        val digest = Authenticator(Credentials("admin", "secret"), "Digest realm=\"cam\", nonce=\"abc\"").header("DESCRIBE", "rtsp://10.0.0.5/live")
        assertTrue(digest, digest.contains("response=\"$expected\""))
        assertFalse(digest.contains("qop="))
        assertEquals("Basic QWxhZGRpbjpvcGVuIHNlc2FtZQ==", Authenticator(Credentials("Aladdin", "open sesame"), "Basic realm=\"x\"").header("GET", "/"))
    }

    @Test
    fun `digest wins over basic whichever comes first`() {
        assertTrue(Authenticator.keepChallenge(null, "Basic realm=\"a\""))
        assertTrue(Authenticator.keepChallenge("Basic realm=\"a\"", "Digest realm=\"a\""))
        assertFalse(Authenticator.keepChallenge("Digest realm=\"a\"", "Basic realm=\"a\""))
    }

    @Test
    fun `a probe match gives the ipv4 device service and the camera's name`() {
        val xml = """<SOAP-ENV:Envelope><SOAP-ENV:Body><d:ProbeMatches><d:ProbeMatch>
            <d:Scopes>onvif://www.onvif.org/type/video_encoder onvif://www.onvif.org/name/IPC%20Cam onvif://www.onvif.org/hardware/C6</d:Scopes>
            <d:XAddrs>http://[fe80::1]/onvif/device_service http://192.168.43.17:8899/onvif/device_service</d:XAddrs>
            </d:ProbeMatch></d:ProbeMatches></SOAP-ENV:Body></SOAP-ENV:Envelope>"""
        val (xaddr, name) = Onvif.parseProbeMatch(xml)!!
        assertEquals("http://192.168.43.17:8899/onvif/device_service", xaddr)
        assertEquals("IPC Cam C6", name)
        assertNull(Onvif.parseProbeMatch("<Envelope><Body><Hello/></Body></Envelope>"))
    }

    @Test
    fun `stream answers are read whatever their prefixes and moved onto the reachable host`() {
        val profiles = """<trt:GetProfilesResponse><trt:Profiles fixed="true" token="Profile_1"><tt:Name>main</tt:Name></trt:Profiles>
            <trt:Profiles token="Profile_2"/></trt:GetProfilesResponse>"""
        assertEquals("Profile_1", Onvif.firstProfileToken(profiles))
        val answer = "<trt:GetStreamUriResponse><trt:MediaUri><tt:Uri>rtsp://10.1.1.9:554/user=admin&amp;channel=1</tt:Uri></trt:MediaUri></trt:GetStreamUriResponse>"
        assertEquals("rtsp://10.1.1.9:554/user=admin&amp;channel=1", Onvif.value(answer, "Uri"))
        assertEquals("rtsp://192.168.43.17:554/live/ch0?a=1", Onvif.onHost("rtsp://10.1.1.9:554/live/ch0?a=1", "192.168.43.17"))
        assertEquals("rtsp://192.168.43.17/live", Onvif.onHost("rtsp://192.168.43.17/live", "192.168.43.17"))
    }

    @Test
    fun `typed addresses`() {
        val plain = Target.parse(" 192.168.1.50 ")!!
        assertEquals("192.168.1.50", plain.host)
        assertNull(plain.port)
        assertNull(plain.url)
        assertEquals(8554, Target.parse("192.168.1.50:8554")!!.port)
        assertNull(Target.parse("192.168.1.50:abc"))
        assertNull(Target.parse(""))

        val rtsp = Target.parse("rtsp://admin:p%40ss@192.168.1.50:8554/stream1?ch=1")!!
        assertEquals("rtsp://192.168.1.50:8554/stream1?ch=1", rtsp.url)
        assertEquals("admin", rtsp.login!!.user)
        assertEquals("p@ss", rtsp.login?.password)
        assertEquals("rtsp://10.0.0.2/", Target.parse("rtsp://10.0.0.2")!!.url)
    }

    @Test
    fun `the sweep covers the phone's 24 and never more`() {
        val phone = LocalNetworks.toInt(LocalNetworks.literal("192.168.43.1")!!)
        val hotspot = LocalNetworks.sweep(phone, 24)
        assertEquals(253, hotspot.size)
        assertFalse(phone in hotspot)
        assertEquals("192.168.43.2", LocalNetworks.toText(hotspot[0]))
        assertEquals("192.168.43.254", LocalNetworks.toText(hotspot[hotspot.size - 1]))

        val wide = LocalNetworks.sweep(LocalNetworks.toInt(LocalNetworks.literal("10.20.30.40")!!), 16)
        assertEquals(253, wide.size)
        assertEquals("10.20.30.1", LocalNetworks.toText(wide[0]))

        assertTrue(LocalNetworks.sameSubnet(phone, LocalNetworks.toInt(LocalNetworks.literal("192.168.43.200")!!), 24))
        assertFalse(LocalNetworks.sameSubnet(phone, LocalNetworks.toInt(LocalNetworks.literal("192.168.44.2")!!), 24))
        assertNull(LocalNetworks.literal("camera.local"))
    }

    @Test
    fun `a password in a stream address is masked for the log`() {
        assertEquals("rtsp://h/live?user=admin&password=***&ch=1", LanSearch.safe("rtsp://h/live?user=admin&password=s3cret&ch=1"))
        assertEquals("rtsp://h/user=admin_password=***_channel=1", LanSearch.safe("rtsp://h/user=admin_password=x_channel=1"))
    }
}
