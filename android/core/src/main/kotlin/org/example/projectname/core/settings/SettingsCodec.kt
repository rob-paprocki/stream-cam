package org.example.projectname.core.settings

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/** The result of [SettingsCodec.decode]. */
sealed interface SettingsLoad {
    /** The settings were read. [migratedFrom] is the older version they were upgraded from, if any. */
    data class Loaded(val settings: AppSettings, val migratedFrom: Int?) : SettingsLoad

    /**
     * The settings can't be used, for the plain-words [reason]. Use the defaults, and keep the saved
     * file as it is: it may come from a newer version of the app that the user goes back to.
     */
    data class Rejected(val reason: String) : SettingsLoad
}

/**
 * Writes settings as a versioned JSON document, `{"version": 1, "video": {...}, ...}`, and reads it
 * back, upgrading older versions through [SettingsMigrations]. Reading never throws.
 */
class SettingsCodec(private val migrations: SettingsMigrations = SettingsMigrations.CURRENT) {

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    fun encode(settings: AppSettings): String {
        val body = json.encodeToJsonElement(AppSettings.serializer(), settings).jsonObject
        val document = JsonObject(mapOf(SettingsMigrations.VERSION_KEY to JsonPrimitive(migrations.currentVersion)) + body)
        return json.encodeToString(JsonObject.serializer(), document) + "\n"
    }

    fun decode(text: String): SettingsLoad {
        val element: JsonElement = try {
            json.parseToJsonElement(text)
        } catch (_: SerializationException) {
            return SettingsLoad.Rejected("The saved settings aren't valid JSON.")
        }
        val document = element as? JsonObject ?: return SettingsLoad.Rejected("The saved settings aren't a settings file.")

        val rawVersion = document[SettingsMigrations.VERSION_KEY]
            ?: return SettingsLoad.Rejected("The saved settings have no version number.")
        val version = (rawVersion as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
            ?: return SettingsLoad.Rejected("The saved settings have a version that isn't a whole number.")
        if (version > migrations.currentVersion) {
            return SettingsLoad.Rejected(
                "The saved settings come from a newer version of the app (settings version $version; this " +
                    "version reads up to ${migrations.currentVersion}). Using the defaults until the app is updated.",
            )
        }
        if (version < 1) return SettingsLoad.Rejected("The saved settings have version $version, which doesn't exist.")

        val upgraded = try {
            migrations.upgrade(document, version)
        } catch (e: RuntimeException) {
            return SettingsLoad.Rejected("The saved settings couldn't be upgraded from version $version.")
        }

        val settings = try {
            json.decodeFromJsonElement(AppSettings.serializer(), JsonObject(upgraded - SettingsMigrations.VERSION_KEY))
        } catch (_: SerializationException) {
            return SettingsLoad.Rejected("Some saved settings couldn't be read.")
        } catch (_: IllegalArgumentException) {
            return SettingsLoad.Rejected("Some saved settings couldn't be read.")
        }

        val problems = SettingsValidator.validate(settings)
        if (problems.isNotEmpty()) return SettingsLoad.Rejected(problems.joinToString(" "))
        return SettingsLoad.Loaded(settings, migratedFrom = version.takeIf { it < migrations.currentVersion })
    }
}
