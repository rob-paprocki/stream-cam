package org.example.projectname.core.validation

import org.example.projectname.core.validation.TestAddresses.ipv4
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FieldRulesTest {

    private fun assertOk(problem: String?, what: String) = assertNull(problem, "expected $what to be accepted")

    private fun assertRefused(problem: String?, what: String): String {
        assertNotNull(problem, "expected $what to be refused")
        assertTrue(problem.endsWith(".") || problem.endsWith("-") || problem.endsWith("://"), "not a sentence: $problem")
        return problem
    }

    @Test
    fun OUT2_srtPassphraseMustBe10To79Characters() {
        assertRefused(FieldRules.checkSrtPassphrase("a".repeat(9)), "9 characters")
        assertOk(FieldRules.checkSrtPassphrase("a".repeat(10)), "10 characters")
        assertOk(FieldRules.checkSrtPassphrase("correct horse battery staple!"), "a phrase with spaces")
        assertOk(FieldRules.checkSrtPassphrase("b".repeat(79)), "79 characters")
        val tooLong = assertRefused(FieldRules.checkSrtPassphrase("c".repeat(80)), "80 characters")
        assertEquals("The passphrase must be 10 to 79 characters long.", tooLong)
        assertRefused(FieldRules.checkSrtPassphrase(""), "an empty passphrase")
    }

    @Test
    fun OUT2_srtPassphraseUsesOnlyPrintableAscii() {
        assertRefused(FieldRules.checkSrtPassphrase("passphrasé-with-accent"), "an accented letter")
        assertRefused(FieldRules.checkSrtPassphrase("tab\tinside-passphrase"), "a tab")
    }

    @Test
    fun srtLatencyIs20To8000Milliseconds() {
        assertRefused(FieldRules.checkSrtLatency(19), "19 ms")
        assertOk(FieldRules.checkSrtLatency(20), "20 ms")
        assertOk(FieldRules.checkSrtLatency(FieldRules.SRT_DEFAULT_LATENCY_MS), "the default")
        assertOk(FieldRules.checkSrtLatency(8000), "8000 ms")
        assertEquals("SRT latency must be from 20 to 8000 ms.", FieldRules.checkSrtLatency(8001))
        assertEquals(120, FieldRules.SRT_DEFAULT_LATENCY_MS)
    }

    @Test
    fun listenerPortsMustBeAbove1024() {
        assertRefused(FieldRules.checkListenPort(80), "port 80")
        assertRefused(FieldRules.checkListenPort(1024), "port 1024")
        assertOk(FieldRules.checkListenPort(1025), "port 1025")
        assertOk(FieldRules.checkListenPort(65535), "port 65535")
        assertRefused(FieldRules.checkListenPort(65536), "port 65536")
        assertRefused(FieldRules.checkListenPort(0), "port 0")
    }

    @Test
    fun remotePortsAre1To65535() {
        assertRefused(FieldRules.checkRemotePort(0), "port 0")
        assertOk(FieldRules.checkRemotePort(1), "port 1")
        assertOk(FieldRules.checkRemotePort(443), "port 443")
        assertOk(FieldRules.checkRemotePort(65535), "port 65535")
        assertRefused(FieldRules.checkRemotePort(65536), "port 65536")
        assertRefused(FieldRules.checkRemotePort(-1), "port -1")
    }

    @Test
    fun hostAcceptsHostNamesIpv4AndBareIpv6() {
        val good = listOf(
            "example.com", "srt.example.com", "media-server", "a.b-c.example.com", "x1.example.com",
            "192.168.100.20", "203.0.113.7", "0.0.0.0", "127.0.0.1",
            "2001:db8::20", "2001:DB8::20", "2001:db8:0:0:0:0:0:1", "2001:db8::", "::1", "::",
        ) + TestAddresses.validShapes
        for (host in good) assertOk(FieldRules.checkHost(host), host)
    }

    @Test
    fun hostRefusesBadNamesAndAddresses() {
        val bad = listOf(
            "", "srt example.com", "[2001:db8::20]", "-example.com", "example-.com", "exa_mple.com",
            "example.com.", ".example.com", "example..com", "a".repeat(64) + ".example.com",
            ipv4(192, 168, 100, 300), "192.168.100", ipv4("192", "168", "100", "020"), "192.168.100.20.5", "example.123",
            "2001:db8:::1", "2001:db8::1::2", "12345::1",
            "2001:db8::1%eth0", "2001:db8::192.168.100.20",
        ) + TestAddresses.invalidShapes
        for (host in bad) assertRefused(FieldRules.checkHost(host), "\"$host\"")
    }

    @Test
    fun hostNamesCanBeAtMost253Characters() {
        val label = "a".repeat(63)
        val ok = listOf(label, label, label, "b".repeat(61)).joinToString(".")
        assertEquals(253, ok.length)
        assertOk(FieldRules.checkHost(ok), "253 characters")
        assertEquals("The host name is too long.", FieldRules.checkHost(ok + "b"))
    }

    @Test
    fun hostProblemsAreExplainedInPlainWords() {
        assertEquals("Enter the IPv6 address without square brackets.", FieldRules.checkHost("[2001:db8::20]"))
        assertEquals(
            "This isn't a valid IPv4 address. It needs four numbers from 0 to 255 with dots between them, and no leading zeros.",
            FieldRules.checkHost(ipv4(192, 168, 100, 256)),
        )
        assertEquals("This isn't a valid IPv6 address.", FieldRules.checkHost("2001:db8:::1"))
    }

    @Test
    fun streamIdIsPrintableAsciiUpTo512Characters() {
        assertOk(FieldRules.checkSrtStreamId("publish:camera1"), "publish:camera1")
        assertOk(FieldRules.checkSrtStreamId("#!::r=live/cam,m=publish"), "an access-control stream ID")
        assertOk(FieldRules.checkSrtStreamId("s".repeat(512)), "512 characters")
        assertRefused(FieldRules.checkSrtStreamId("s".repeat(513)), "513 characters")
        assertRefused(FieldRules.checkSrtStreamId(""), "an empty stream ID")
        assertRefused(FieldRules.checkSrtStreamId("publish cam"), "a space")
    }

    @Test
    fun rtspPathPartsUseSafeCharacters() {
        for (path in listOf("live", "cam/main", "cam_1/main-2.hd", "a~b", "a/b/c/d/e/f/g/h")) assertOk(FieldRules.checkRtspPath(path), path)
        for (path in listOf("", "/live", "live/", "cam//main", "live stream", "../live", ".hidden", "a/b/c/d/e/f/g/h/i", "caméra")) {
            assertRefused(FieldRules.checkRtspPath(path), "\"$path\"")
        }
        assertOk(FieldRules.checkRtspPath("p".repeat(64)), "64 characters")
        assertRefused(FieldRules.checkRtspPath("p".repeat(65)), "65 characters")
    }

    @Test
    fun rtmpAppAndStreamNameAreSinglePartsWithSafeCharacters() {
        assertOk(FieldRules.checkRtmpApp("live"), "live")
        assertOk(FieldRules.checkRtmpStreamName("cam-1.hd"), "cam-1.hd")
        assertRefused(FieldRules.checkRtmpApp("live/cam"), "a slash")
        assertRefused(FieldRules.checkRtmpApp("live stream"), "a space")
        assertRefused(FieldRules.checkRtmpStreamName(""), "an empty name")
        assertRefused(FieldRules.checkRtmpStreamName("_cam"), "a leading underscore")
    }

    @Test
    fun usernamesHaveNoSpacesOrColons() {
        assertOk(FieldRules.checkUsername("viewer"), "viewer")
        assertOk(FieldRules.checkUsername("first.last@example.com"), "an address-like name")
        assertRefused(FieldRules.checkUsername("user:name"), "a colon")
        assertRefused(FieldRules.checkUsername("user name"), "a space")
        assertRefused(FieldRules.checkUsername(""), "an empty name")
        assertRefused(FieldRules.checkUsername("u".repeat(65)), "65 characters")
    }

    @Test
    fun OUT6_secretsEnteredByTheUserAreCheckedBeforeStoring() {
        assertOk(FieldRules.checkPassword("an example password"), "a password with spaces")
        assertRefused(FieldRules.checkPassword(""), "an empty password")
        assertRefused(FieldRules.checkPassword("two\nlines"), "a line break")
        assertOk(FieldRules.checkStreamKey("live_0000_example"), "a stream key")
        assertRefused(FieldRules.checkStreamKey("has space"), "a space in a stream key")
        assertRefused(FieldRules.checkStreamKey(""), "an empty stream key")
    }

    @Test
    fun profileNamesAndIds() {
        assertOk(FieldRules.checkProfileName("Studio SRT"), "Studio SRT")
        assertOk(FieldRules.checkProfileName("Café stream"), "an accented name")
        assertRefused(FieldRules.checkProfileName(""), "an empty name")
        assertRefused(FieldRules.checkProfileName(" Leading space"), "a leading space")
        assertRefused(FieldRules.checkProfileName("Two\nlines"), "a line break")
        assertRefused(FieldRules.checkProfileName("n".repeat(81)), "81 characters")
        assertOk(FieldRules.checkProfileId("0b6c1d2e-profile_1.v2"), "an ID")
        assertRefused(FieldRules.checkProfileId("has space"), "a space")
        assertRefused(FieldRules.checkProfileId(""), "an empty ID")
    }

    @Test
    fun profileNamesCountCharactersNotUtf16Units() {
        // U+1F3A5 takes two UTF-16 units. The schema counts it once, and so must the app.
        val camera = String(Character.toChars(0x1F3A5))
        assertOk(FieldRules.checkProfileName(camera.repeat(41)), "41 emoji")
        assertOk(FieldRules.checkProfileName(camera.repeat(80)), "80 emoji")
        assertEquals("The name can be at most 80 characters.", FieldRules.checkProfileName(camera.repeat(81)))
        assertRefused(FieldRules.checkProfileName("Studio\n"), "a line break at the end")
    }
}
