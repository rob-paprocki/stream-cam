package org.example.projectname.core.profile

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.example.projectname.core.validation.FieldError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class ProfileCodecTest {

    private val samples = listOf(
        DestinationProfile(
            id = "srt-caller",
            name = "Studio SRT",
            network = NetworkChoice.WIFI,
            allowFallback = true,
            destination = Destination.SrtCaller(
                host = "2001:db8::20",
                port = 9000,
                streamId = "publish:camera1",
                latencyMs = 250,
                encryption = SrtEncryption.AES_256,
                passphraseRef = SecretRef("secret-srt-1"),
            ),
        ),
        DestinationProfile(
            id = "srt-listener",
            name = "Listener",
            network = NetworkChoice.CELLULAR,
            destination = Destination.SrtListener(port = 9001, encryption = SrtEncryption.AES_128, passphraseRef = SecretRef("secret-srt-2")),
        ),
        DestinationProfile(
            id = "rtmp-push",
            name = "Live service",
            destination = Destination.RtmpPush(url = "rtmps://live.example.com/app", streamKeyRef = SecretRef("secret-key-1")),
        ),
        DestinationProfile(
            id = "rtsp-push",
            name = "Media server",
            network = NetworkChoice.ETHERNET,
            destination = Destination.RtspPush(url = "rtsp://192.168.100.20:8554/cam", username = "example", passwordRef = SecretRef("secret-pw-1")),
        ),
        DestinationProfile(
            id = "rtsp-pull",
            name = "RTSP server",
            destination = Destination.RtspPull(path = "cam/main", username = "viewer", passwordRef = SecretRef("secret-pw-2")),
        ),
        DestinationProfile(
            id = "rtmp-pull",
            name = "RTMP server",
            destination = Destination.RtmpPull(port = 1936, app = "live", streamName = "cam"),
        ),
    )

    private fun export(profile: DestinationProfile): String = when (val result = ProfileCodec.export(profile)) {
        is ProfileExportResult.Exported -> result.json
        is ProfileExportResult.Rejected -> fail("export refused: ${result.errors}")
    }

    private fun imported(text: String): DestinationProfile = when (val result = ProfileCodec.import(text)) {
        is ProfileImportResult.Imported -> result.profile
        is ProfileImportResult.Rejected -> fail("import refused: ${result.summary}")
    }

    private fun rejected(text: String): List<FieldError> = when (val result = ProfileCodec.import(text)) {
        is ProfileImportResult.Imported -> fail("expected a refusal, got ${result.profile}")
        is ProfileImportResult.Rejected -> result.errors
    }

    private fun profileJson(destination: String, extra: String = ""): String =
        """{"schemaVersion": 1, "id": "p1", "name": "Profile"$extra, "destination": $destination}"""

    @Test
    fun OUT8_everyDestinationTypeSurvivesExportAndImport() {
        assertEquals(destinationTypeNames, samples.map { typeName(it.destination) }.toSet())
        for (profile in samples) {
            assertEquals(profile.withoutSecrets(), imported(export(profile)), profile.id)
        }
    }

    @Test
    fun OUT6_exportsLeaveOutSecretsAndTheirReferences() {
        for (profile in samples) {
            val json = export(profile)
            assertFalse("Ref" in json, "${profile.id} export mentions a secret reference:\n$json")
            assertFalse("secret-" in json, "${profile.id} export holds a secret ID:\n$json")
        }
    }

    @Test
    fun OUT6_anImportedProfileAsksForTheSecretsItNeeds() {
        val srt = imported(export(samples[0]))
        assertNull((srt.destination as Destination.SrtCaller).passphraseRef)
        assertEquals(listOf(SecretKind.SRT_PASSPHRASE), srt.missingSecrets())
        assertEquals(listOf(SecretKind.PASSWORD), imported(export(samples[3])).missingSecrets())
        assertEquals(emptyList(), imported(export(samples[2])).missingSecrets())
        assertEquals(emptyList(), samples[0].missingSecrets())
        val unencrypted = samples[0].copy(destination = Destination.SrtCaller(host = "srt.example.com", port = 9000))
        assertEquals(emptyList(), unencrypted.missingSecrets())
    }

    @Test
    fun OUT8_importRefusesSecretReferences() {
        val errors = rejected(profileJson("""{"type": "rtmpPush", "url": "rtmp://live.example.com/app", "streamKeyRef": "secret-key-1"}"""))
        assertEquals(listOf("destination.streamKeyRef"), errors.map { it.field })
        assertTrue("can't carry secrets" in errors.single().message)
    }

    @Test
    fun OUT8_exportWritesTheVersionAndEveryDefault() {
        val profile = DestinationProfile(id = "p1", name = "Defaults", destination = Destination.SrtListener())
        val document = Json.parseToJsonElement(export(profile)).jsonObject
        assertEquals(JsonPrimitive(1), document["schemaVersion"])
        assertEquals(JsonPrimitive("auto"), document["network"])
        assertEquals(JsonPrimitive(false), document["allowFallback"])
        val destination = document.getValue("destination").jsonObject
        assertEquals(
            JsonObject(
                mapOf(
                    "type" to JsonPrimitive("srtListener"),
                    "port" to JsonPrimitive(9000),
                    "latencyMs" to JsonPrimitive(120),
                    "encryption" to JsonPrimitive("none"),
                ),
            ),
            destination,
        )
    }

    @Test
    fun NET1_networkDefaultsToAuto() {
        val profile = imported(profileJson("""{"type": "srtCaller", "host": "srt.example.com", "port": 9000}"""))
        assertEquals(NetworkChoice.AUTO, profile.network)
        assertEquals(
            listOf(NetworkChoice.AUTO, NetworkChoice.WIFI, NetworkChoice.CELLULAR, NetworkChoice.ETHERNET),
            listOf("auto", "wifi", "cellular", "ethernet").map {
                imported(profileJson("""{"type": "srtListener"}""", """, "network": "$it"""")).network
            },
        )
    }

    @Test
    fun NET4_fallbackToAnotherNetworkIsOffUnlessAskedFor() {
        assertFalse(imported(profileJson("""{"type": "srtListener"}""")).allowFallback)
        assertFalse(DestinationProfile(id = "p", name = "P", destination = Destination.SrtListener()).allowFallback)
        assertTrue(imported(profileJson("""{"type": "srtListener"}""", """, "allowFallback": true""")).allowFallback)
    }

    @Test
    fun OUT8_destinationDefaultsMatchTheSpec() {
        val listener = imported(profileJson("""{"type": "srtListener"}""")).destination as Destination.SrtListener
        assertEquals(Destination.SrtListener(port = 9000, latencyMs = 120, encryption = SrtEncryption.NONE), listener)
        val rtsp = imported(profileJson("""{"type": "rtspPull", "path": "live"}""")).destination as Destination.RtspPull
        assertEquals(8554, rtsp.port)
        val rtmp = imported(profileJson("""{"type": "rtmpPull", "app": "live", "streamName": "cam"}""")).destination as Destination.RtmpPull
        assertEquals(1935, rtmp.port)
        val caller = imported(profileJson("""{"type": "srtCaller", "host": "srt.example.com", "port": 9000}""")).destination
        assertEquals(Destination.SrtCaller(host = "srt.example.com", port = 9000, streamId = null, latencyMs = 120), caller)
    }

    @Test
    fun OUT2_srtEncryptionKeyLengths() {
        assertEquals(listOf(0, 16, 24, 32), SrtEncryption.entries.map { it.keyLengthBytes })
    }

    @Test
    fun OUT8_aNewerFormatSaysToUpdateTheApp() {
        val errors = rejected("""{"schemaVersion": 2, "id": "p1", "name": "From the future", "destination": {"type": "srtListener"}}""")
        assertEquals(
            listOf(
                FieldError(
                    "schemaVersion",
                    "This profile was saved by a newer version of the app (profile format 2). Update the app, then import it again.",
                ),
            ),
            errors,
        )
    }

    @Test
    fun OUT8_filesThatAreNotProfilesAreRefusedInPlainWords() {
        assertEquals("This file isn't valid JSON, so it can't be a destination profile.", rejected("{not json").single().message)
        assertEquals("This file doesn't hold a destination profile.", rejected("[1, 2]").single().message)
        assertEquals(
            "This file has no schemaVersion, so it isn't a destination profile from this app.",
            rejected("""{"id": "p1"}""").single().message,
        )
        assertEquals("The schemaVersion in this file must be a whole number.", rejected("""{"schemaVersion": "1"}""").single().message)
        assertEquals("This file is too large to be a destination profile.", rejected(" ".repeat(ProfileCodec.MAX_FILE_CHARS + 1)).single().message)
    }

    @Test
    fun OUT8_missingUnknownAndWrongTypeFieldsAreNamed() {
        val errors = rejected(
            """{"schemaVersion": 1, "id": "p1", "alowFallback": true, "network": "bluetooth",
               "destination": {"type": "srtCaller", "port": "9000", "mode": "caller", "streamId": null}}""",
        )
        assertEquals(
            listOf(
                FieldError("alowFallback", "The file has a field this app doesn't know: \"alowFallback\"."),
                FieldError("network", "\"network\" must be one of: auto, wifi, cellular, ethernet."),
                FieldError("destination.port", "\"destination.port\" must be a whole number."),
                FieldError("destination.mode", "The file has a field this app doesn't know: \"destination.mode\"."),
                FieldError("destination.streamId", "\"destination.streamId\" is null. Leave the field out instead."),
                FieldError("destination.host", "The file is missing \"destination.host\"."),
                FieldError("name", "The file is missing \"name\"."),
            ),
            errors,
        )
    }

    @Test
    fun OUT8_unknownDestinationTypesListTheKnownOnes() {
        val errors = rejected(profileJson("""{"type": "hls"}"""))
        assertEquals(
            listOf(
                FieldError(
                    "destination.type",
                    "\"destination.type\" must be one of: rtmpPull, rtmpPush, rtspPull, rtspPush, srtCaller, srtListener.",
                ),
            ),
            errors,
        )
    }

    @Test
    fun OUT8_fieldRuleProblemsAreReportedPerField() {
        val errors = rejected(profileJson("""{"type": "srtCaller", "host": "192.168.100.20.5", "port": 9000, "latencyMs": 10}"""))
        assertEquals(listOf("destination.host", "destination.latencyMs"), errors.map { it.field })
        assertEquals("SRT latency must be from 20 to 8000 ms.", errors[1].message)
    }

    @Test
    fun OUT8_pushUrlsNeedAPathAndNoQuery() {
        assertEquals(
            "Add the app name after the host, like rtmp://live.example.com/live",
            rejected(profileJson("""{"type": "rtmpPush", "url": "rtmp://live.example.com"}""")).single().message,
        )
        assertEquals(
            "Remove the part of the address from ? onwards. Keys and passwords go in their own fields.",
            rejected(profileJson("""{"type": "rtmpPush", "url": "rtmp://live.example.com/app?key=example"}""")).single().message,
        )
        assertEquals(
            "Add the stream path after the host, like rtsp://192.168.100.20:8554/live",
            rejected(profileJson("""{"type": "rtspPush", "url": "rtsp://media.example.com"}""")).single().message,
        )
    }

    @Test
    fun OUT8_exportRefusesAProfileThatImportWouldRefuse() {
        val bad = DestinationProfile(id = "p1", name = "", destination = Destination.SrtListener(port = 80))
        val result = assertIs<ProfileExportResult.Rejected>(ProfileCodec.export(bad))
        assertEquals(listOf("name", "destination.port"), result.errors.map { it.field })
    }

    @Test
    fun UI7_inboundDestinationsShowTheAddressToConnectTo() {
        assertEquals("srt://192.168.100.20:9000", Destination.SrtListener().connectAddress("192.168.100.20"))
        assertEquals("srt://[2001:db8::20]:9000", Destination.SrtListener().connectAddress("2001:db8::20"))
        assertEquals("rtsp://192.168.100.20:8554/cam/main", Destination.RtspPull(path = "cam/main").connectAddress("192.168.100.20"))
        assertEquals("rtmp://192.168.100.20:1935/live/cam", Destination.RtmpPull(app = "live", streamName = "cam").connectAddress("192.168.100.20"))
        assertNull(Destination.SrtCaller(host = "srt.example.com", port = 9000).connectAddress("192.168.100.20"))
        assertEquals(listOf(false, true, false, false, true, true), samples.map { it.destination.isInbound })
    }

    @Test
    fun OUT6_secretReferencesNeverPrintTheirId() {
        assertFalse("secret-srt-1" in SecretRef("secret-srt-1").toString())
        assertFalse("secret-srt-1" in samples[0].toString())
    }
}
