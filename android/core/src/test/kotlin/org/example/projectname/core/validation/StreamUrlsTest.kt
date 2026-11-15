package org.example.projectname.core.validation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

class StreamUrlsTest {

    private fun valid(text: String, schemes: Set<UrlScheme>): StreamUrl = when (val result = StreamUrls.parse(text, schemes)) {
        is Parsed.Valid -> result.value
        is Parsed.Invalid -> fail("expected $text to parse, got: ${result.message}")
    }

    private fun invalid(text: String, schemes: Set<UrlScheme>): String = when (val result = StreamUrls.parse(text, schemes)) {
        is Parsed.Valid -> fail("expected $text to be refused, got $result")
        is Parsed.Invalid -> result.message
    }

    private val rtmp = UrlScheme.RTMP_ANY
    private val rtsp = UrlScheme.RTSP_ANY

    @Test
    fun readsRtmpAndRtmpsWithTheirUsualPorts() {
        val plain = valid("rtmp://live.example.com/app", rtmp)
        assertEquals(UrlScheme.RTMP, plain.scheme)
        assertEquals("live.example.com", plain.host)
        assertEquals(null, plain.port)
        assertEquals(1935, plain.effectivePort)
        assertEquals(listOf("app"), plain.pathSegments)

        val secure = valid("rtmps://live.example.com/app/sub", rtmp)
        assertEquals(UrlScheme.RTMPS, secure.scheme)
        assertEquals(443, secure.effectivePort)
        assertEquals(listOf("app", "sub"), secure.pathSegments)
    }

    @Test
    fun readsRtspAndRtspsWithPortAndPath() {
        val url = valid("rtsp://192.168.100.20:8554/cam/main", rtsp)
        assertEquals("192.168.100.20", url.host)
        assertEquals(8554, url.port)
        assertEquals(listOf("cam", "main"), url.pathSegments)
        assertEquals(322, valid("rtsps://media.example.com/live", rtsp).effectivePort)
        assertEquals(554, valid("rtsp://media.example.com/live", rtsp).effectivePort)
    }

    @Test
    fun readsIpv6AddressesInSquareBrackets() {
        val url = valid("rtsp://[2001:db8::30]:8554/live", rtsp)
        assertEquals("2001:db8::30", url.host)
        assertTrue(url.hostIsIpv6)
        assertEquals(8554, url.port)
        assertEquals("::1", valid("rtmp://[::1]/live", rtmp).host)
        assertEquals(null, valid("rtmp://[2001:db8::1]/live", rtmp).port)
    }

    @Test
    fun refusesIpv6WithoutSquareBrackets() {
        val message = invalid("rtsp://2001:db8::30:8554/live", rtsp)
        assertEquals("Put an IPv6 address in square brackets, like rtsp://[2001:db8::1].", message)
    }

    @Test
    fun refusesBrokenSquareBrackets() {
        assertEquals("The IPv6 address is missing its closing ].", invalid("rtsp://[2001:db8::30/live", rtsp))
        assertEquals("The address in square brackets isn't a valid IPv6 address.", invalid("rtsp://[example.com]/live", rtsp))
        assertEquals("The square brackets are empty. Put an IPv6 address in them.", invalid("rtsp://[]/live", rtsp))
        assertEquals("Only a colon and a port number can follow the ].", invalid("rtsp://[2001:db8::30]8554/live", rtsp))
        invalid("rtsp://[2001:db8::30%25eth0]/live", rtsp)
        invalid("rtsp://example.com]/live", rtsp)
    }

    @Test
    fun refusesNamesAndPasswordsInTheAddress() {
        val message = "Put the user name and password in their own fields, not in the address."
        assertEquals(message, invalid("rtsp://example:example@192.168.100.30/live", rtsp))
        assertEquals(message, invalid("rtmp://example@live.example.com/app", rtmp))
    }

    @Test
    fun refusesOtherSchemesWithAHint() {
        assertEquals("Start the address with rtmp:// or rtmps://", invalid("http://live.example.com/app", rtmp))
        assertEquals("Start the address with rtsp:// or rtsps://", invalid("rtmp://live.example.com/app", rtsp))
        assertEquals("Start the address with rtmp:// or rtmps://", invalid("live.example.com/app", rtmp))
        assertEquals("Write the start of the address in lower case: rtmp://", invalid("RTMP://live.example.com/app", rtmp))
        assertEquals("Start the address with srt://", invalid("", setOf(UrlScheme.SRT)).substringAfter(". "))
    }

    @Test
    fun refusesBadPorts() {
        for (port in listOf("0", "65536", "99999", "08554", "", "port")) {
            invalid("rtsp://media.example.com:$port/live", rtsp)
        }
        assertEquals(65535, valid("rtsp://media.example.com:65535/live", rtsp).port)
        assertEquals(1, valid("rtsp://media.example.com:1/live", rtsp).port)
    }

    @Test
    fun refusesBadHosts() {
        invalid("rtmp:///app", rtmp)
        invalid("rtmp://:1935/app", rtmp)
        invalid("rtmp://192.168.100.20.5/app", rtmp)
        invalid("rtmp://${TestAddresses.ipv4(192, 168, 100, 300)}/app", rtmp)
        invalid("rtmp://exa_mple.com/app", rtmp)
        invalid("rtmp://live.example.com./app", rtmp)
    }

    @Test
    fun refusesBadPaths() {
        assertEquals("Remove the slash at the end of the address.", invalid("rtmp://live.example.com/app/", rtmp))
        assertEquals("The address has two slashes in a row. Remove one.", invalid("rtmp://live.example.com//app", rtmp))
        invalid("rtmp://live.example.com/app/stream%20key", rtmp)
        invalid("rtmp://live.example.com/app/caméra", rtmp)
        assertEquals("Remove the part of the address from # onwards.", invalid("rtmp://live.example.com/app#top", rtmp))
    }

    @Test
    fun refusesSpacesAndLineBreaks() {
        assertEquals("The address can't contain spaces or line breaks.", invalid("rtmp://live.example.com/my app", rtmp))
        invalid(" rtmp://live.example.com/app", rtmp)
        invalid("rtmp://live.example.com/app\n", rtmp)
    }

    @Test
    fun readsQueryParametersAndDecodesThem() {
        val url = valid("srt://192.168.100.20:9000?streamid=%23!%3A%3Ar%3Dlive&latency=120&flag", setOf(UrlScheme.SRT))
        assertTrue(url.hasQuery)
        assertEquals(mapOf("streamid" to "#!::r=live", "latency" to "120", "flag" to ""), url.queryParameters)
        assertEquals("a+b", valid("srt://192.168.100.20:9000?streamid=a+b", setOf(UrlScheme.SRT)).queryParameters["streamid"])
        invalid("srt://192.168.100.20:9000?streamid=%zz", setOf(UrlScheme.SRT))
        invalid("srt://192.168.100.20:9000?streamid=%2", setOf(UrlScheme.SRT))
        assertFalse(valid("rtmp://live.example.com/app", rtmp).hasQuery)
    }

    @Test
    fun OUT2_readsSrtCallerAddressesWithStreamIdAndPassphrase() {
        assertEquals(
            Parsed.Valid(SrtAddress("192.168.100.20", 9000, "publish:cam", "example-passphrase")),
            StreamUrls.parseSrt("srt://192.168.100.20:9000?streamid=publish:cam&passphrase=example-passphrase"),
        )
        assertEquals(Parsed.Valid(SrtAddress("2001:db8::20", 9000, null, null)), StreamUrls.parseSrt("srt://[2001:db8::20]:9000"))
    }

    @Test
    fun OUT2_srtStreamIdsMayStartWithAnUnencodedHash() {
        assertEquals(
            Parsed.Valid(SrtAddress("srt.example.com", 9000, "#!::r=live/cam,m=publish", null)),
            StreamUrls.parseSrt("srt://srt.example.com:9000?streamid=#!::r=live/cam,m=publish"),
        )
        assertEquals("Remove the part of the address from # onwards.", invalid("srt://srt.example.com:9000#x", setOf(UrlScheme.SRT)))
        assertEquals("Remove the part of the address from # onwards.", invalid("rtsp://media.example.com/live?a=#b", rtsp))
    }

    @Test
    fun OUT2_refusesSrtAddressesWithAShortPassphraseOrNoPort() {
        assertEquals(
            Parsed.Invalid("The passphrase must be 10 to 79 characters long."),
            StreamUrls.parseSrt("srt://srt.example.com:9000?passphrase=short"),
        )
        assertEquals(Parsed.Invalid("Add the port number, like srt://srt.example.com:9000"), StreamUrls.parseSrt("srt://srt.example.com"))
        assertEquals(Parsed.Invalid("Add the port number, like srt://[2001:db8::20]:9000"), StreamUrls.parseSrt("srt://[2001:db8::20]"))
        assertIs<Parsed.Invalid>(StreamUrls.parseSrt("srt://srt.example.com:9000/live"))
        assertIs<Parsed.Invalid>(StreamUrls.parseSrt("rtmp://srt.example.com:9000"))
    }

    @Test
    fun OUT6_parsedAddressesNeverPrintTheirSecrets() {
        val address = SrtAddress("srt.example.com", 9000, "publish:cam", "example-passphrase")
        assertFalse("example-passphrase" in address.toString())
        assertFalse("publish:cam" in address.toString())
        val url = valid("srt://srt.example.com:9000?passphrase=example-passphrase", setOf(UrlScheme.SRT))
        assertFalse("example-passphrase" in url.toString())
    }

    @Test
    fun UI7_formatPutsIpv6InSquareBrackets() {
        assertEquals("srt://192.168.100.20:9000", StreamUrls.format(UrlScheme.SRT, "192.168.100.20", 9000))
        assertEquals("rtsp://[2001:db8::20]:8554/cam/main", StreamUrls.format(UrlScheme.RTSP, "2001:db8::20", 8554, listOf("cam", "main")))
        assertEquals("rtmp://live.example.com/app", StreamUrls.format(UrlScheme.RTMP, "live.example.com", null, listOf("app")))
        assertEquals("[2001:db8::20]", StreamUrls.formatHost("[2001:db8::20]"))
    }

    @Test
    fun formattedUrlsReadBackTheSame() {
        val text = StreamUrls.format(UrlScheme.RTSP, "2001:db8::20", 8554, listOf("cam", "main"))
        val url = valid(text, rtsp)
        assertEquals("2001:db8::20", url.host)
        assertEquals(8554, url.port)
        assertEquals(listOf("cam", "main"), url.pathSegments)
    }
}
