package org.example.projectname.core.log

import org.example.projectname.core.validation.TestAddresses.hexRun
import org.example.projectname.core.validation.TestAddresses.ipv4
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Every value in these tests is an example: example.com, 192.168.100.0/24, the documentation ranges. */
class RedactorTest {

    private fun assertRedacted(input: String, expected: String) {
        assertEquals(expected, Redactor.redact(input), "redacting: $input")
    }

    private fun assertGone(input: String, vararg secrets: String) {
        val output = Redactor.redact(input)
        for (secret in secrets) assertFalse(secret in output, "\"$secret\" is still in: $output")
    }

    @Test
    fun OUT6_srtPassphrasesAreRemoved() {
        assertRedacted(
            "connecting to srt://srt.example.com:9000?streamid=publish:cam&passphrase=example-passphrase-1&latency=120",
            "connecting to srt://srt.example.com:9000?streamid=<redacted>&passphrase=<redacted>&latency=120",
        )
        assertRedacted("SRTO_PASSPHRASE passphrase = example passphrase two", "SRTO_PASSPHRASE passphrase = <redacted>")
        assertRedacted("SrtCaller(passphrase=examplePassphrase3, latency=120)", "SrtCaller(passphrase=<redacted>, latency=120)")
    }

    @Test
    fun OUT6_passphrasesWithSpacesAmpersandsAndQuotesAreRemovedWhole() {
        // SRT passphrases can hold any printable ASCII, so a bare value runs to the next field, not the next space.
        assertRedacted(
            "SrtDestination(host=srt.example.com, passphrase=correct horse battery staple, latency=120)",
            "SrtDestination(host=srt.example.com, passphrase=<redacted>, latency=120)",
        )
        assertRedacted("password=pa ss word", "password=<redacted>")
        assertRedacted("passphrase=abc&def", "passphrase=<redacted>")
        assertRedacted("srt://srt.example.com:9000?passphrase=abc&def&latency=120", "srt://srt.example.com:9000?passphrase=<redacted>&latency=120")
        // A comma ends the value only when a space and the next field's name follow it, as in Kotlin's
        // toString. SRT access-control stream IDs use commas without spaces, and stay redacted whole.
        assertRedacted("passphrase=abc,def=ghi, latency=120", "passphrase=<redacted>, latency=120")
        assertRedacted(
            "srt://srt.example.com:9000?streamid=#!::r=live,s=examplesession&passphrase=abc&latency=120",
            "srt://srt.example.com:9000?streamid=<redacted>&passphrase=<redacted>&latency=120",
        )
        assertRedacted("config{secret=example secret 8} applied", "config{secret=<redacted>} applied")
        assertRedacted("check(pwd=example pwd 9) failed", "check(pwd=<redacted>) failed")
        assertRedacted("passwd = example passwd 10, user = viewer", "passwd = <redacted>, user = viewer")
    }

    @Test
    fun OUT6_quotedSecretsWithEscapedOrStrayQuotesAreRemovedWhole() {
        assertRedacted("""{"passphrase":"abc\"def ghi","latency":120}""", """{"passphrase":"<redacted>","latency":120}""")
        assertRedacted("""passphrase="abc\"def ghi" latency=120""", """passphrase="<redacted>" latency=120""")
        assertRedacted("""password='it\'s example 11', next""", """password='<redacted>', next""")
        // A quote inside a value that isn't escaped can't end it: the value runs on to the end of the field.
        assertRedacted("""passwd='it's example 12'""", """passwd='<redacted>'""")
        assertRedacted("""secret="unclosed example 13""", """secret=<redacted>""")
        // Nor can a quote followed by more words: Kotlin's toString ends a field only at ", name=".
        assertRedacted("""SrtCaller(passphrase="Tom" says hi, latency=120)""", "SrtCaller(passphrase=<redacted>, latency=120)")
        assertRedacted("""passphrase="Tom" says hi, latency=120""", "passphrase=<redacted>, latency=120")
        assertRedacted("""passphrase='Tom' says hi""", "passphrase=<redacted>")
        // Spaces before the end of the field are fine.
        assertRedacted("""{ "password": "example 14" }""", """{ "password": "<redacted>" }""")
        assertRedacted("""token="example 15" ; path=/""", """token="<redacted>" ; path=/""")
    }

    @Test
    fun OUT6_secretsInEscapedJsonAreRemoved() {
        // JSON logged inside a JSON string: every quote has a backslash in front.
        assertRedacted("""{\"passphrase\":\"abc def\",\"latency\":120}""", """{\"passphrase\":\"<redacted>\",\"latency\":120}""")
        assertRedacted("""{\"password\": \"a\\\"b c\"}""", """{\"password\": \"<redacted>\"}""")
        assertRedacted("""{\"streamKey\":\"example key 16\"}""", """{\"streamKey\":\"<redacted>\"}""")
        assertRedacted(
            """headers={\"Authorization\":\"Bearer exampletoken\",\"Accept\":\"*/*\"}""",
            """headers={\"Authorization\":\"<redacted>\",\"Accept\":\"*/*\"}""",
        )
        assertRedacted("""{\"serialNumber\": \"EXAMPLE SERIAL 08\"}""", """{\"serialNumber\": \"<redacted>\"}""")
        assertGone("""{\"passphrase\":\"Tom\\\" says \\\"hi\"}""", "Tom", "says", "hi")
    }

    @Test
    fun OUT6_quotedHeaderValuesFollowedByMoreTextAreRemovedWhole() {
        assertRedacted("""Authorization: "Bearer" exampletoken""", "Authorization: <redacted>")
    }

    @Test
    fun OUT6_rtmpStreamKeysAreRemoved() {
        assertRedacted("publish rtmp://live.example.com/app/live_0000_examplekey", "publish rtmp://live.example.com/app/<redacted>")
        assertRedacted("rtmps://live.example.com:443/app/sub/examplekey2?x=1", "rtmps://live.example.com:443/app/sub/<redacted>?x=1")
        assertRedacted("RTMP://192.168.100.20/live/examplekey3.", "RTMP://<ip>/live/<redacted>")
        assertRedacted("url=\"rtmp://live.example.com/app/examplekey4\"", "url=\"rtmp://live.example.com/app/<redacted>\"")
        assertRedacted("rtmp://live.example.com/app/examplekey5/", "rtmp://live.example.com/app/<redacted>/")
        // With only one segment, that segment is taken for the key.
        assertRedacted("rtmp://live.example.com/examplekey6 ok", "rtmp://live.example.com/<redacted> ok")
        assertRedacted("rtmp://live.example.com/ and rtmp://live.example.com", "rtmp://live.example.com/ and rtmp://live.example.com")
        // Two URLs with nothing between them that ends a URL: each loses its own key.
        assertRedacted(
            """["rtmp://live.example.com/app/examplekey7","rtmps://live.example.com/app/examplekey8"]""",
            """["rtmp://live.example.com/app/<redacted>","rtmps://live.example.com/app/<redacted>"]""",
        )
        assertRedacted("rtmp://live.example.com//rtmp://live.example.com/app/examplekey9", "rtmp://live.example.com//rtmp://live.example.com/app/<redacted>")
    }

    @Test
    fun OUT6_rtmpUrlsWithThousandsOfSegmentsAreHandledQuickly() {
        // The JVM's regex engine recurses once per segment for a repeated group, and ran out of stack here.
        val path = "a/".repeat(4_000)
        val start = System.nanoTime()
        assertRedacted("publish rtmp://live.example.com/$path" + "examplekey", "publish rtmp://live.example.com/$path<redacted>")
        assertRedacted("rtmp://".repeat(2_000), "rtmp://".repeat(2_000))
        val millis = (System.nanoTime() - start) / 1_000_000
        assertTrue(millis < 2_000, "took $millis ms")
    }

    @Test
    fun OUT6_namesAndPasswordsInUrlsAreRemoved() {
        assertRedacted("rtsp://exampleuser:examplepass@192.168.100.30:8554/live", "rtsp://<redacted>@<ip>:8554/live")
        assertRedacted("rtsp://exampleuser@media.example.com/live", "rtsp://<redacted>@media.example.com/live")
        assertRedacted("rtsp://exampleuser:p@ss:w0rd@media.example.com/live", "rtsp://<redacted>@media.example.com/live")
        assertRedacted("rtmp://exampleuser:examplepass@live.example.com/app/examplekey", "rtmp://<redacted>@live.example.com/app/<redacted>")
        // A scheme can follow a dash, a dot or a digit that no letter comes before.
        assertRedacted("open 1-rtsp://exampleuser@media.example.com/live", "open 1-rtsp://<redacted>@media.example.com/live")
        assertRedacted("open ...rtsp://exampleuser@media.example.com/live", "open ...rtsp://<redacted>@media.example.com/live")
        assertRedacted("url=srt://exampleuser:examplepass@srt.example.com", "url=srt://<redacted>@srt.example.com")
    }

    @Test
    fun OUT6_longDottedAndDashedRunsAreHandledQuickly() {
        // Every letter after a dot or dash used to start a new try at a URL scheme, which then ran to the
        // end of the run: quadratic in the run's length.
        val lines = listOf("a.".repeat(8_000), "a-".repeat(8_000), "a.b-".repeat(4_000), "a+".repeat(8_000))
        for (line in lines) assertEquals(Redactor.MAX_LINE_LENGTH, line.length)
        val start = System.nanoTime()
        repeat(10) { for (line in lines) assertRedacted(line, line) }
        val millis = (System.nanoTime() - start) / 1_000_000
        assertTrue(millis < 2_000, "took $millis ms")
    }

    @Test
    fun OUT6_passwordKeyAndTokenValuesAreRemoved() {
        assertRedacted("login password=examplepass1 ok", "login password=<redacted>")
        assertRedacted("?key=examplekey1&token=exampletoken1&mode=live", "?key=<redacted>&token=<redacted>&mode=live")
        assertRedacted("Profile(streamKey=examplekey2, username=viewer)", "Profile(streamKey=<redacted>, username=viewer)")
        assertRedacted("""{"password":"example pass 3","user":"viewer"}""", """{"password":"<redacted>","user":"viewer"}""")
        assertRedacted("x-api-key: examplekey4 and more", "x-api-key: <redacted>")
        assertRedacted("[persist.example.token]: [exampletoken2]", "[persist.example.token]: <redacted>")
        assertRedacted("Pwd = examplepass5", "Pwd = <redacted>")
        assertRedacted("auth=exampleauth", "auth=<redacted>")
        assertRedacted("secret: example secret 6", "secret: <redacted>")
        assertRedacted("client_secret='example7'", "client_secret='<redacted>'")
    }

    @Test
    fun OUT6_authorizationHeadersAreRemoved() {
        assertRedacted("Authorization: Bearer example.token.value", "Authorization: <redacted>")
        assertRedacted("authorization: Basic ZXhhbXBsZTpleGFtcGxl\nnext line", "authorization: <redacted>\nnext line")
        assertRedacted("Proxy-Authorization: Digest username=\"viewer\"", "Proxy-Authorization: <redacted>")
        assertRedacted("""headers={"Authorization":"Bearer exampletoken","Accept":"*/*"}""", """headers={"Authorization":"<redacted>","Accept":"*/*"}""")
        assertRedacted("Cookie: session=examplesession", "Cookie: <redacted>")
    }

    @Test
    fun OUT6_usbSerialNumbersAreRemoved() {
        assertRedacted("  iSerialNumber           3 EXAMPLESERIAL01", "  iSerialNumber           3 <redacted>")
        assertRedacted("serial=EXAMPLESERIAL02 vendor=0x1234", "serial=<redacted> vendor=0x1234")
        assertRedacted(
            "UsbDevice[mName=/dev/bus/usb/001/002,mSerialNumber=EXAMPLESERIAL03,mVersion=2.00]",
            "UsbDevice[mName=/dev/bus/usb/001/002,mSerialNumber=<redacted>,mVersion=2.00]",
        )
        assertRedacted("[ro.serialno]: [EXAMPLESERIAL04]", "[ro.serialno]: [<redacted>]")
        assertRedacted("Serial number: EXAMPLESERIAL05", "Serial number: <redacted>")
        assertRedacted("""{"serialNumber": "EXAMPLE SERIAL 06"}""", """{"serialNumber": "<redacted>"}""")
        assertRedacted("S:  SerialNumber=EXAMPLESERIAL07", "S:  SerialNumber=<redacted>")
    }

    @Test
    fun OUT6_serialNumbersWithNoClosingQuoteAreRemovedToTheEndOfTheLine() {
        assertRedacted("serial=\"EXAMPLESERIAL09", "serial=<redacted>")
        assertRedacted("serial=\"EXAMPLE SERIAL 10, vendor=0x1234", "serial=<redacted>")
        assertRedacted("mSerialNumber: 'EXAMPLESERIAL11", "mSerialNumber: <redacted>")
        assertRedacted("""{\"serial\":\"EXAMPLESERIAL12""", """{\"serial\":<redacted>""")
        assertRedacted("serial=\"EXAMPLESERIAL13\nnext line", "serial=<redacted>\nnext line")
        // A closed quote still ends the value.
        assertRedacted("serial=\"EXAMPLESERIAL14\" vendor=0x1234", "serial=\"<redacted>\" vendor=0x1234")
    }

    @Test
    fun OUT6_ipv4AddressesAreRemoved() {
        assertRedacted("Wi-Fi address 192.168.100.20, gateway 192.168.100.1.", "Wi-Fi address <ip>, gateway <ip>.")
        assertRedacted("connected to 203.0.113.7:9000 from 198.51.100.23", "connected to <ip>:9000 from <ip>")
        assertRedacted("srt://192.0.2.10:9000", "srt://<ip>:9000")
        assertRedacted("ip=${ipv4("192", "168", "100", "020")}/24", "ip=<ip>/24")
    }

    @Test
    fun OUT6_ipv6AddressesAreRemoved() {
        assertRedacted("address 2001:db8::10 is up", "address <ip> is up")
        assertRedacted("srt://[2001:db8::1]:9000", "srt://[<ip>]:9000")
        assertRedacted("2001:db8:0:0:0:0:0:1, 2001:DB8:85A3::8A2E:370:7334.", "<ip>, <ip>.")
        assertRedacted("link 2001:db8::1%wlan0 ready", "link <ip> ready")
        assertRedacted("(2001:db8::abcd)", "(<ip>)")
    }

    @Test
    fun OUT6_ipv6AddressesNextToAColonAreRemoved() {
        assertRedacted("addr:2001:db8::1", "addr:<ip>")
        assertRedacted("inet6 addr:2001:db8::1/64 Scope:Global", "inet6 addr:<ip>/64 Scope:Global")
        assertRedacted("peer 2001:db8::2: closed", "peer <ip>: closed")
        assertRedacted("link 2001:db8::1%wlan0: up", "link <ip>: up")
        assertRedacted("ip6:2001:db8::3", "ip6:<ip>")
        // A word after the colon isn't another group, even when it starts with hex letters.
        assertRedacted("2001:db8::1:connected", "<ip>:connected")
        assertRedacted("peer 2001:db8::2:active", "peer <ip>:active")
        assertRedacted("2001:db8::a:deadline passed", "<ip>:deadline passed")
        assertRedacted("link 2001:db8::1%wlan0:connected", "link <ip>:connected")
        assertRedacted("addr:2001:db8::4:_tmp", "addr:<ip>:_tmp")
        // Nor is a word made only of hex letters, when it is too long to be a group.
        assertRedacted("2001:db8::1:added", "<ip>:added")
        assertRedacted("peer 2001:db8::1:faded.", "peer <ip>:faded.")
        assertRedacted("2001:db8::1:deadbeef", "<ip>:deadbeef")
        // A group that fits is taken as part of the address, and the address is still removed.
        assertRedacted("2001:db8::1:dead", "<ip>")
        // A zone or a word ends the address, so a colon and a group after it are something else.
        assertRedacted("link 2001:db8::1%wlan0:5", "link <ip>:5")
        assertRedacted("2001:db8::1:connected:5", "<ip>:connected:5")
        assertRedacted("host 2001:db8::1.local", "host <ip>.local")
    }

    @Test
    fun OUT6_loopbackAndUnspecifiedAddressesStay() {
        val text = "listening on 0.0.0.0:8554 and [::]:9000, test server 127.0.0.1:1935, ::1 and [::1]:8554"
        assertRedacted(text, text)
    }

    @Test
    fun OUT6_macAddressesAreRemoved() {
        assertRedacted("wlan0 00:00:5E:00:53:01 up", "wlan0 <mac> up")
        assertRedacted("adapter 00-00-5e-00-53-02.", "adapter <mac>.")
        assertRedacted("mac=00:00:5E:00:53:FF,", "mac=<mac>,")
    }

    @Test
    fun OUT6_macAddressesNextToAColonAreRemoved() {
        assertRedacted("mac:00:00:5E:00:53:01", "mac:<mac>")
        assertRedacted("MAC 00:00:5E:00:53:01: link up", "MAC <mac>: link up")
        assertRedacted("dev 00:00:5E:00:53:01:", "dev <mac>:")
        assertRedacted("wlan0:00:00:5E:00:53:02 up", "wlan0:<mac> up")
        assertRedacted("bssid:00-00-5e-00-53-03: joined", "bssid:<mac>: joined")
        // A word after the colon isn't another group, even when it starts with hex letters.
        assertRedacted("MAC 00:00:5E:00:53:01:connected", "MAC <mac>:connected")
        assertRedacted("MAC 00:00:5E:00:53:01:active", "MAC <mac>:active")
        assertRedacted("MAC 00:00:5E:00:53:01:disconnected", "MAC <mac>:disconnected")
        assertRedacted("MAC 00:00:5E:00:53:01:1st", "MAC <mac>:1st")
        assertRedacted("adapter 00-00-5e-00-53-02-enabled", "adapter <mac>-enabled")
        // Nor is a word made only of hex letters: a group of a longer run has one or two digits.
        for (word in listOf("added", "faded", "bad", "dead", "cafe", "beef")) {
            assertRedacted("MAC 00:00:5E:00:53:01:$word", "MAC <mac>:$word")
            assertRedacted("MAC 00-00-5E-00-53-01-$word", "MAC <mac>-$word")
        }
        assertRedacted("dump " + hexRun(6) + ":cafe", "dump <mac>:cafe")
        assertRedacted("MAC 00:00:5E:00:53:01:dead-beef", "MAC <mac>:dead-beef")
    }

    @Test
    fun OUT6_macAddressesAfterANameAndADashAreRemoved() {
        // File names put a name and a dash in front, and sometimes a copy number after.
        assertRedacted("hw-00:00:5E:00:53:02", "hw-<mac>")
        assertRedacted("scan-00-00-5e-00-53-01-1.json", "scan-<mac>-1.json")
        assertRedacted("capture-00-00-5E-00-53-03.mp4", "capture-<mac>.mp4")
        assertRedacted("wlan0-00:00:5E:00:53:04 up", "wlan0-<mac> up")
        assertRedacted("log-cafe-00-00-5e-00-53-05.txt", "log-cafe-<mac>.txt")
        assertRedacted("x1-00:00:5E:00:53:06", "x1-<mac>")
        // A pair after the dash is read as part of a longer run, as a copy number 01 would be too.
        assertRedacted("scan-00-00-5e-00-53-01-02.json", "scan-00-00-5e-00-53-01-02.json")
    }

    @Test
    fun OUT6_timesAndHexRunsNextToColonsStay() {
        // The hex runs are built at run time: six pairs in a row look like a MAC address to the
        // repository's personal-value check, whatever follows them.
        val lines = listOf(
            "at:12:34:56 the encoder restarted",
            "time:12:34:56.789, next 10:15:30:",
            "timecode 10:15:30:12, tc:10:15:30:13",
            "Elapsed: 01:02:03:",
            "data:" + hexRun(7),
            "data:" + hexRun(9),
            "descriptor " + hexRun(9) + ": done",
            "dump " + hexRun(6) + ":1",
            "dump " + hexRun(6) + ":f",
            "dump " + hexRun(6, "-") + "-1",
            "dump " + hexRun(7) + ":connected",
            "build:cafe:babe:1",
            "fe:" + hexRun(6),
            // EUI-64 IDs and key fingerprints, alone and after a name and a dash.
            "eui " + hexRun(8, "-"),
            "fingerprint " + hexRun(16) + " and " + hexRun(32, "-"),
            "id-" + hexRun(7, "-") + ".bin",
            "id-" + hexRun(8, "-") + " ok",
            // A lone digit or pair before the dash is part of the run too.
            "run 1-" + hexRun(6, "-"),
            "run ab-" + hexRun(6, "-"),
        )
        for (line in lines) assertRedacted(line, line)
    }

    @Test
    fun OUT6_ordinaryTextIsUntouched() {
        val lines = listOf(
            "Encoder started: 1920x1080 at 30 fps, H.264, 6000 kbps, keyframe every 2 s",
            "Keyframe interval: 2 s, bitrate: 6 Mbps, latency: 120 ms, encryption: aes128",
            "RootEncoder 2.8.2 on API 29, Gradle 9.9.1, version 0.0.0",
            "Recording saved as PROJECT_NAME_20261114_101530.mp4 in Movies",
            "Elapsed 10:15:30, aspect 16:9, timestamp 2026-11-14T10:15:30.123Z",
            "The serial port is not used. Authorization failed, check the password.",
            "Kotlin String::class and std::string are fine",
            "rtsp://media.example.com:8554/live and srt://srt.example.com:9000?latency=120",
            "USB device 1234:5678 attached, product name: Example Capture",
            "Stream state: reconnecting (attempt 3, waiting 5 s)",
            "deserialize: ok; Serializer: none; keys: 3 ignored",
            "",
        )
        for (line in lines) assertRedacted(line, line)
    }

    @Test
    fun OUT6_redactingTwiceChangesNothing() {
        val text = "rtsp://u:p@192.168.100.20/live passphrase=example-passphrase rtmp://live.example.com/app/key " +
            "Authorization: Bearer x\niSerialNumber 3 EXAMPLESERIAL\n00:00:5E:00:53:01 2001:db8::1"
        val once = Redactor.redact(text)
        assertEquals(once, Redactor.redact(once))
        assertTrue(Redactor.REDACTED in once && Redactor.IP in once && Redactor.MAC in once)
    }

    @Test
    fun OUT6_longRunsOfHexAndColonsAreHandledQuickly() {
        val dump = "descriptor " + "0a:".repeat(50_000) + "zz " + "1.".repeat(50_000) + " " +
            "zzzzzzzzzz:0a".repeat(50_000) + " " + "mac:".repeat(50_000) + " " + "password=" + ", a".repeat(50_000)
        val start = System.nanoTime()
        Redactor.redact(dump)
        val millis = (System.nanoTime() - start) / 1_000_000
        assertTrue(millis < 5_000, "took $millis ms")
    }

    @Test
    fun OUT6_manyLinesJustUnderTheCapAreHandledQuickly() {
        // The same kinds of runs as above, and the shapes the secret rules loop over, each on its own line
        // so that none is cut: the rules themselves must stay fast on lines of any length up to the cap.
        val lines = listOf(
            "descriptor " + "0a:".repeat(5_000),
            "1.".repeat(7_900),
            "zzzzzzzzzz:0a".repeat(1_200),
            "mac:".repeat(3_900),
            "password=" + ", a".repeat(5_000),
            "password=" + "a,".repeat(7_900),
            "password=&" + "a&".repeat(7_900),
            "password=\"" + "\\\"".repeat(7_900),
            "{\\\"password\\\":\\\"" + "\\\\\\\"".repeat(3_900),
            "added:".repeat(2_600),
            "2001:db8::1:connected ".repeat(700),
            "connected:".repeat(1_500),
            "00:00:5E:00:53:01:".repeat(880),
        )
        for (line in lines) assertTrue(line.length <= Redactor.MAX_LINE_LENGTH, "a sample line is ${line.length} long")
        val text = buildString { while (length < 650_000) for (line in lines) append(line).append('\n') }
        val start = System.nanoTime()
        val out = Redactor.redact(text)
        val millis = (System.nanoTime() - start) / 1_000_000
        assertFalse("[line cut:" in out, "a line under the cap was cut")
        assertTrue(millis < 5_000, "took $millis ms")
    }

    @Test
    fun OUT6_linesLongerThanTheCapAreCutBeforeMatching() {
        // Android's regex engine (ICU) runs out of stack on lines of about 90,000 characters with some of the
        // secret rules, where the JVM doesn't, so a JVM test can only pin the cap that keeps lines short.
        assertEquals(16_000, Redactor.MAX_LINE_LENGTH)
        val exact = "y".repeat(16_000)
        assertRedacted(exact, exact)
        assertRedacted("y".repeat(16_001), exact + " [line cut: 1 character]")
        assertRedacted(
            "first 192.168.100.20\n" + "y".repeat(20_000) + "\r\nlast 192.168.100.21",
            "first <ip>\n$exact [line cut: 4000 characters]\r\nlast <ip>",
        )
        // The note comes after the redacted part, so a secret value can't take the note with it.
        assertRedacted("password=" + "x".repeat(100_000), "password=<redacted> [line cut: 84009 characters]")
        // A cut never falls inside a word: the word it would fall in goes whole, and the note counts it.
        assertRedacted("a".repeat(15_990) + " pwd=examplepass6 more", "a".repeat(15_990) + "  [line cut: 21 characters]")
        // A secret with a space, cut in a later word, is still removed, and what was cut off never reaches
        // the output.
        assertRedacted("a".repeat(15_985) + " pwd=example pass six more", "a".repeat(15_985) + " pwd=<redacted> [line cut: 13 characters]")
        // A pair of UTF-16 units is never cut in half.
        val emoji = "\uD83D\uDE00"
        assertRedacted("y".repeat(15_999) + emoji + "y", "y".repeat(15_999) + " [line cut: 3 characters]")
    }

    @Test
    fun OUT6_theCutMovesBackToTheStartOfAWordOnlyAShortWayIn() {
        // A word that started up to 512 characters before the cut goes whole. One that started earlier
        // is cut where the limit falls: no word that long is a value the rules look for.
        val cap = Redactor.MAX_LINE_LENGTH
        val near = "z".repeat(cap - 513) + " " + "q".repeat(600)
        assertRedacted(near, "z".repeat(cap - 513) + "  [line cut: 600 characters]")
        val far = "z".repeat(cap - 514) + " " + "q".repeat(600)
        assertRedacted(far, "z".repeat(cap - 514) + " " + "q".repeat(513) + " [line cut: 87 characters]")
        // Commas, semicolons and closing brackets end a word as spaces do.
        for (end in listOf(",", ";", ")", "]", "}", "\t")) {
            val line = "z".repeat(cap - 6) + end + "pwd=examplepass7"
            assertRedacted(line, "z".repeat(cap - 6) + end + " [line cut: 16 characters]")
        }
    }

    @Test
    fun OUT6_aValueTheCutFallsInLeavesNothingBehind() {
        // Each value is put where the cut falls at every offset inside it. What is kept of it must be
        // redacted whole, or not be there at all. The secrets are upper case, so no piece of them can
        // turn up in what the redactor writes.
        data class Case(val value: String, val whole: String, val secrets: List<String>)
        val cases = listOf(
            Case("srt://EXAMPLEUSER:EXAMPLEPASS@203.0.113.5:9000", "srt://<redacted>@<ip>:9000", listOf("EXAMPLEUSER", "EXAMPLEPASS", "203.0.113.5")),
            Case("rtsp://EXAMPLEUSER@media.example.com/live", "rtsp://<redacted>@media.example.com/live", listOf("EXAMPLEUSER")),
            // User info may hold the characters that end a word elsewhere, so the cut can't stop at them.
            Case("rtsp://EXAMPLEUSER:EXAMPLE,PASS@203.0.113.5/cam", "rtsp://<redacted>@<ip>/cam", listOf("EXAMPLEUSER", "EXAMPLE,PASS", "203.0.113.5")),
            Case("rtsp://EXAMPLEUSER:EXAMPLE;PASS@203.0.113.5/cam", "rtsp://<redacted>@<ip>/cam", listOf("EXAMPLEUSER", "EXAMPLE;PASS", "203.0.113.5")),
            Case("srt://EXAMPLEUSER:EXAMPLE)PASS}QX@203.0.113.5:9000", "srt://<redacted>@<ip>:9000", listOf("EXAMPLEUSER", "EXAMPLE)PASS}QX", "203.0.113.5")),
            Case("serial=\"EXAMPLESERIAL\"", "serial=\"<redacted>\"", listOf("EXAMPLESERIAL")),
            Case("mSerialNumber='EXAMPLESERIAL'", "mSerialNumber='<redacted>'", listOf("EXAMPLESERIAL")),
            Case("serial=\"EXAMPLE SERIAL QX9\"", "serial=\"<redacted>\"", listOf("EXAMPLE SERIAL QX9")),
            Case("00:00:5E:00:53:AB", "<mac>", listOf("00:00:5E:00:53:AB")),
            Case("00-00-5E-00-53-AB", "<mac>", listOf("00-00-5E-00-53-AB")),
            Case("203.0.113.77", "<ip>", listOf("203.0.113.77")),
            Case("2001:db8:1234:5678:9abc:def0:1234:5678", "<ip>", listOf("2001:db8:1234:5678:9abc:def0:1234:5678")),
            Case("2001:db8::1234:5678%wlan0", "<ip>", listOf("2001:db8::1234:5678%wlan0")),
            Case("passphrase=EXAMPLEPASSPHRASE", "passphrase=<redacted>", listOf("EXAMPLEPASSPHRASE")),
            Case("passphrase=\"EXAMPLE PASS PHRASE\"", "passphrase=\"<redacted>\"", listOf("EXAMPLE PASS PHRASE")),
            Case(
                "srt://srt.example.com:9000?streamid=EXAMPLESTREAM&passphrase=EXAMPLEPASSPHRASE",
                "srt://srt.example.com:9000?streamid=<redacted>&passphrase=<redacted>",
                listOf("EXAMPLESTREAM", "EXAMPLEPASSPHRASE"),
            ),
        )
        val cap = Redactor.MAX_LINE_LENGTH
        for (case in cases) {
            val pieces = case.secrets.flatMap { it.windowed(3) }.filter { ' ' !in it }
            for (offset in 0..case.value.length) {
                val filler = "z".repeat(cap - offset - 1) + " "
                val output = Redactor.redact(filler + case.value + " end")
                assertTrue(output.startsWith(filler) && output.endsWith(" characters]"), "cut at $offset of ${case.value}")
                val kept = output.substring(filler.length).substringBefore(" [line cut: ")
                if (offset == case.value.length) assertEquals(case.whole, kept, "cut right after ${case.value}")
                for (piece in pieces) assertFalse(piece in kept, "\"$piece\" is left of ${case.value}, cut at $offset: $kept")
            }
        }
    }

    @Test
    fun OUT6_aCutLineRedactedAgainKeepsItsNote() {
        val once = Redactor.redact("password=" + "x".repeat(20_000))
        assertEquals(once, Redactor.redact(once))
        val plain = Redactor.redact("y".repeat(20_000))
        assertEquals(plain, Redactor.redact(plain))
        // Text added in front of a cut line (a timestamp, a tag) is cut from its end, and the note counts it.
        assertEquals("y".repeat(15_995) + " [line cut: 4005 characters]", Redactor.redact("tag: " + plain).drop(5))
        // A note written into an ordinary line stays where it is.
        assertRedacted("password=example7 [line cut: 12 characters]", "password=<redacted> [line cut: 12 characters]")
    }
}
