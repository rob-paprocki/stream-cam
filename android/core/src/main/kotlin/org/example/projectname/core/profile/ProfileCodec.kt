@file:OptIn(ExperimentalSerializationApi::class)

package org.example.projectname.core.profile

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.example.projectname.core.validation.FieldError

/** The result of [ProfileCodec.import]. */
sealed interface ProfileImportResult {
    /**
     * The file was read. The profile carries no secrets, so ask for [DestinationProfile.missingSecrets]
     * before streaming with it.
     */
    data class Imported(val profile: DestinationProfile) : ProfileImportResult

    /** The file can't be used. Each error says what is wrong in plain words. */
    data class Rejected(val errors: List<FieldError>) : ProfileImportResult {
        val summary: String get() = errors.joinToString(" ") { it.message }
    }
}

/** The result of [ProfileCodec.export]. */
sealed interface ProfileExportResult {
    data class Exported(val json: String) : ProfileExportResult

    /** The profile has problems, and a file made from it would be refused on import. */
    data class Rejected(val errors: List<FieldError>) : ProfileExportResult
}

/**
 * Writes destination profiles to, and reads them from, the JSON files described by
 * `shared/profile.schema.json` (spec OUT-8). One file holds one profile.
 *
 * Exports never contain secrets, nor even references to them (spec OUT-6). Imports refuse any file that
 * does, so a file from someone else can't point a profile at one of this device's saved secrets.
 */
object ProfileCodec {
    /** Profile files are small; anything bigger than this isn't one. */
    const val MAX_FILE_CHARS: Int = 64 * 1024

    internal const val SCHEMA_VERSION_KEY = "schemaVersion"

    internal val json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = false
        classDiscriminator = ProfileShape.TYPE_KEY
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    fun export(profile: DestinationProfile): ProfileExportResult {
        val safe = profile.withoutSecrets()
        val errors = ProfileValidator.validate(safe)
        if (errors.isNotEmpty()) return ProfileExportResult.Rejected(errors)
        return ProfileExportResult.Exported(json.encodeToString(DestinationProfile.serializer(), safe) + "\n")
    }

    fun import(text: String): ProfileImportResult {
        if (text.length > MAX_FILE_CHARS) return rejected("This file is too large to be a destination profile.")
        val element: JsonElement = try {
            json.parseToJsonElement(text)
        } catch (_: SerializationException) {
            return rejected("This file isn't valid JSON, so it can't be a destination profile.")
        }
        if (element !is JsonObject) return rejected("This file doesn't hold a destination profile.")

        checkVersion(element)?.let { return rejected(it, field = SCHEMA_VERSION_KEY) }

        val shapeErrors = ProfileShape.check(element, DestinationProfile.serializer().descriptor)
        if (shapeErrors.isNotEmpty()) return ProfileImportResult.Rejected(shapeErrors)

        val profile = try {
            json.decodeFromJsonElement(DestinationProfile.serializer(), element)
        } catch (_: SerializationException) {
            return rejected("This file doesn't match the destination profile format.")
        } catch (_: IllegalArgumentException) {
            return rejected("This file doesn't match the destination profile format.")
        }

        val errors = ProfileValidator.validate(profile)
        if (errors.isNotEmpty()) return ProfileImportResult.Rejected(errors)
        return ProfileImportResult.Imported(profile)
    }

    private fun checkVersion(document: JsonObject): String? {
        val raw = document[SCHEMA_VERSION_KEY]
            ?: return "This file has no schemaVersion, so it isn't a destination profile from this app."
        val version = (raw as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
            ?: return "The schemaVersion in this file must be a whole number."
        return when {
            version > PROFILE_SCHEMA_VERSION ->
                "This profile was saved by a newer version of the app (profile format $version). " +
                    "Update the app, then import it again."
            version < 1 -> "This file has profile format $version, which doesn't exist."
            // Older formats would be upgraded here. Format 1 is the first, so there are none yet.
            else -> null
        }
    }

    private fun rejected(message: String, field: String = "") =
        ProfileImportResult.Rejected(listOf(FieldError(field, message)))
}
