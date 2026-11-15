@file:OptIn(ExperimentalSerializationApi::class)

package org.example.projectname.core.profile

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.elementDescriptors
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.example.projectname.core.validation.FieldRules
import org.example.projectname.core.validation.TestAddresses
import org.example.projectname.core.validation.TestAddresses.ipv4
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Keeps shared/profile.schema.json and the Kotlin model in step: the same fields, the same required
 * fields, the same enum values and defaults, and patterns that accept exactly what [FieldRules] accepts.
 */
class SchemaParityTest {
    private val schema: JsonObject = Json.parseToJsonElement(SharedFiles.schema.readText()).jsonObject
    private val defs: JsonObject = schema.getValue("\$defs").jsonObject

    private fun resolve(node: JsonElement): JsonObject {
        val obj = node.jsonObject
        val ref = obj["\$ref"]?.jsonPrimitive?.content ?: return obj
        require(ref.startsWith("#/\$defs/")) { "unexpected ref $ref" }
        return defs.getValue(ref.removePrefix("#/\$defs/")).jsonObject
    }

    private fun properties(node: JsonObject): Map<String, JsonObject> =
        node.getValue("properties").jsonObject.mapValues { resolve(it.value) }

    private fun required(node: JsonObject): Set<String> =
        node.getValue("required").jsonArray.map { it.jsonPrimitive.content }.toSet()

    private fun enumValues(name: String): List<String> = defs.getValue(name).jsonObject.getValue("enum").jsonArray.map { it.jsonPrimitive.content }

    private fun pattern(node: JsonObject): Regex = Regex(node.getValue("pattern").jsonPrimitive.content)

    private fun destinationSchemas(): Map<String, JsonObject> =
        defs.getValue("destination").jsonObject.getValue("oneOf").jsonArray.associate { option ->
            val resolved = resolve(option)
            properties(resolved).getValue("type").getValue("const").jsonPrimitive.content to resolved
        }

    private fun subclassDescriptors(): Map<String, SerialDescriptor> =
        Destination.serializer().descriptor.getElementDescriptor(1).elementDescriptors.associateBy { it.serialName }

    private fun SerialDescriptor.plainFields(): Set<String> =
        (0 until elementsCount).filterNot { ProfileShape.isSecretReference(getElementDescriptor(it)) }.map { getElementName(it) }.toSet()

    private fun SerialDescriptor.requiredFields(): Set<String> =
        (0 until elementsCount).filterNot { isElementOptional(it) }.map { getElementName(it) }.toSet()

    @Test
    fun OUT8_enumValuesMatch() {
        assertEquals(NetworkChoice.serializer().descriptor.elementNames.toList(), enumValues("network"))
        assertEquals(SrtEncryption.serializer().descriptor.elementNames.toList(), enumValues("encryption"))
    }

    @Test
    fun OUT8_destinationTypesMatch() {
        assertEquals(destinationTypeNames, destinationSchemas().keys)
    }

    @Test
    fun OUT8_topLevelFieldsMatch() {
        val descriptor = DestinationProfile.serializer().descriptor
        assertEquals(descriptor.plainFields(), properties(schema).keys)
        // schemaVersion has a default in Kotlin, but a file must always say which format it is.
        assertEquals(descriptor.requiredFields() + "schemaVersion", required(schema))
    }

    @Test
    fun OUT8_destinationFieldsMatchAndSecretsAreNotInTheSchema() {
        val descriptors = subclassDescriptors()
        for ((type, node) in destinationSchemas()) {
            val descriptor = descriptors.getValue(type)
            assertEquals(descriptor.plainFields() + "type", properties(node).keys, type)
            assertEquals(descriptor.requiredFields() + "type", required(node), type)
            assertTrue(properties(node).keys.none { it.endsWith("Ref") || it in setOf("passphrase", "password", "streamKey") }, type)
        }
    }

    @Test
    fun OUT8_defaultsMatch() {
        val minimal = mapOf(
            "srtCaller" to Destination.SrtCaller(host = "srt.example.com", port = 9000),
            "srtListener" to Destination.SrtListener(),
            "rtmpPush" to Destination.RtmpPush(url = "rtmp://live.example.com/app"),
            "rtspPush" to Destination.RtspPush(url = "rtsp://media.example.com/live"),
            "rtspPull" to Destination.RtspPull(path = "live"),
            "rtmpPull" to Destination.RtmpPull(app = "live", streamName = "cam"),
        )
        var checked = 0
        for ((type, node) in destinationSchemas()) {
            val encoded = ProfileCodec.json.encodeToJsonElement(Destination.serializer(), minimal.getValue(type)).jsonObject
            for ((field, property) in properties(node)) {
                val default = property["default"] ?: continue
                assertEquals(default, encoded[field], "$type.$field")
                checked++
            }
        }
        val profile = ProfileCodec.json.encodeToJsonElement(
            DestinationProfile.serializer(),
            DestinationProfile(id = "p", name = "P", destination = Destination.SrtListener()),
        ).jsonObject
        for ((field, property) in properties(schema)) {
            val default = property["default"] ?: continue
            assertEquals(default, profile[field], field)
            checked++
        }
        assertTrue(checked >= 9, "expected the schema to document every default, found $checked")
    }

    @Test
    fun OUT8_numberRangesMatch() {
        fun range(node: JsonObject) = node.getValue("minimum").jsonPrimitive.content.toInt()..node.getValue("maximum").jsonPrimitive.content.toInt()
        val types = destinationSchemas()
        assertEquals(FieldRules.MIN_PORT..FieldRules.MAX_PORT, range(properties(types.getValue("srtCaller")).getValue("port")))
        assertEquals(FieldRules.SRT_MIN_LATENCY_MS..FieldRules.SRT_MAX_LATENCY_MS, range(defs.getValue("latencyMs").jsonObject))
        for (type in listOf("srtListener", "rtspPull", "rtmpPull")) {
            assertEquals(FieldRules.MIN_LISTEN_PORT..FieldRules.MAX_PORT, range(properties(types.getValue(type)).getValue("port")), type)
        }
        assertEquals(PROFILE_SCHEMA_VERSION.toString(), properties(schema).getValue("schemaVersion").getValue("const").jsonPrimitive.content)
    }

    /** A host name of exactly [length] characters, in labels of at most 63. */
    private fun hostNameOfLength(length: Int): String {
        val labels = ArrayList<String>()
        var left = length
        while (left > 0) {
            val label = minOf(63, left)
            labels += "h".repeat(label)
            left -= label + 1
        }
        return labels.joinToString(".").also { check(it.length == length) { "can't build a host of $length characters" } }
    }

    private val host253 = hostNameOfLength(253)
    private val host254 = hostNameOfLength(254)

    /** U+1F3A5, which takes two UTF-16 units but is one character to the schema. */
    private val camera = String(Character.toChars(0x1F3A5))

    /** For each sample, the schema pattern and the Kotlin rule must both accept it or both refuse it. */
    private fun assertSameVerdicts(what: String, regex: Regex, samples: List<String>, kotlinAccepts: (String) -> Boolean) {
        val disagreements = samples.filter { regex.containsMatchIn(it) != kotlinAccepts(it) }.map { sample ->
            "\"$sample\": schema ${if (regex.containsMatchIn(sample)) "accepts" else "refuses"}, Kotlin ${if (kotlinAccepts(sample)) "accepts" else "refuses"}"
        }
        assertEquals(emptyList(), disagreements, what)
        assertTrue(samples.any(kotlinAccepts) && !samples.all(kotlinAccepts), "$what samples need both verdicts")
    }

    @Test
    fun OUT8_hostPatternMatchesTheHostRule() {
        val samples = listOf(
            "example.com", "srt.example.com", "media-server", "a.b-c.example.com", "x1.example.com", "a", "a-b",
            "0.0.0.0", "192.168.100.20", "203.0.113.255", "198.51.100.1", "127.0.0.1",
            "2001:db8::20", "2001:DB8::20", "2001:db8:0:0:0:0:0:1", "2001:db8::", "::1", "::", "2001:db8:1:2:3:4::5",
            "", " ", "srt example.com", "[2001:db8::20]", "-example.com", "example-.com", "exa_mple.com", "example.com.",
            ".example.com", "example..com", "a".repeat(64) + ".example.com", "a".repeat(63) + ".example.com",
            ipv4(192, 168, 100, 300), "192.168.100", ipv4("192", "168", "100", "020"), "192.168.100.20.5", "example.123",
            ipv4(256, 0, 0, 1), "2001:db8:::1", "2001:db8::1::2", "12345::1", ":2001:db8::1", "2001:db8::1%eth0",
            "2001:db8::192.168.100.20", "g::1", host253, host254, "example.com\n", "192.168.100.20\n", "2001:db8::20\n",
        ) + TestAddresses.validShapes + TestAddresses.invalidShapes
        assertSameVerdicts("host", pattern(defs.getValue("host").jsonObject), samples) { FieldRules.checkHost(it) == null }
    }

    @Test
    fun OUT8_urlPatternsMatchTheUrlRules() {
        val types = destinationSchemas()
        val hosts = listOf(
            "live.example.com", "192.168.100.20", "[2001:db8::30]", "[::1]", "2001:db8::30", "[example.com]", "exa_mple.com", "",
            host253, host254,
        )
        val tails = listOf("/app", "/app/sub", ":1935/live", ":0/live", ":65535/a", ":65536/a", ":08554/a", ":/a", "", "/", "/app/", "//app", "/a b", "/app?key=x", "/app#x", "/_definst_", "/~a.b-c")
        fun samples(schemes: List<String>) = schemes.flatMap { scheme -> hosts.flatMap { host -> tails.map { "$scheme://$host$it" } } } +
            listOf("RTMP://live.example.com/app", "http://live.example.com/app", "rtmp://example:example@live.example.com/app") +
            schemes.map { "$it://live.example.com/app\n" }

        assertSameVerdicts("rtmpPush.url", pattern(properties(types.getValue("rtmpPush")).getValue("url")), samples(listOf("rtmp", "rtmps", "rtsp"))) {
            ProfileValidator.validate(Destination.RtmpPush(url = it)).isEmpty()
        }
        assertSameVerdicts("rtspPush.url", pattern(properties(types.getValue("rtspPush")).getValue("url")), samples(listOf("rtsp", "rtsps", "srt"))) {
            ProfileValidator.validate(Destination.RtspPush(url = it)).isEmpty()
        }
    }

    @Test
    fun OUT8_textPatternsMatchTheFieldRules() {
        val top = properties(schema)
        val types = destinationSchemas()
        assertSameVerdicts(
            "name",
            pattern(top.getValue("name")),
            listOf(
                "Studio", "Café stream", "a", "n".repeat(80), "n".repeat(81), "", " lead", "trail ", "two\nlines", "tab\there",
                "del\u007f", "Studio\n", "Studio\r\n", camera.repeat(41), camera.repeat(80), camera.repeat(81), "n" + camera.repeat(79),
            ),
        ) { FieldRules.checkProfileName(it) == null }
        assertSameVerdicts(
            "id",
            pattern(top.getValue("id")),
            listOf("p1", "a.b_c-d", "i".repeat(64), "i".repeat(65), "", "has space", "slash/no", "é", "p1\n"),
        ) { FieldRules.checkProfileId(it) == null }
        assertSameVerdicts(
            "username",
            pattern(defs.getValue("username").jsonObject),
            listOf("viewer", "first.last@example.com", "u".repeat(64), "u".repeat(65), "", "user:name", "user name", "é", "viewer\n"),
        ) { FieldRules.checkUsername(it) == null }
        assertSameVerdicts(
            "streamId",
            pattern(properties(types.getValue("srtCaller")).getValue("streamId")),
            listOf("publish:cam", "#!::r=live,m=publish", "s".repeat(512), "s".repeat(513), "", "a b", "é", "publish:cam\n"),
        ) { FieldRules.checkSrtStreamId(it) == null }
        assertSameVerdicts(
            "rtspPull.path",
            pattern(properties(types.getValue("rtspPull")).getValue("path")),
            listOf("live", "cam/main", "a/b/c/d/e/f/g/h", "a/b/c/d/e/f/g/h/i", "p".repeat(64), "p".repeat(65), "", "/live", "live/", "a//b", "../a", ".a", "a b", "_a", "live\n"),
        ) { FieldRules.checkRtspPath(it) == null }
        assertSameVerdicts(
            "rtmpPull.app",
            pattern(properties(types.getValue("rtmpPull")).getValue("app")),
            listOf("live", "live-2.hd", "a~b", "p".repeat(64), "p".repeat(65), "", "live/cam", "_live", "live stream", "live\n"),
        ) { FieldRules.checkRtmpApp(it) == null && FieldRules.checkRtmpStreamName(it) == null }
    }
}
