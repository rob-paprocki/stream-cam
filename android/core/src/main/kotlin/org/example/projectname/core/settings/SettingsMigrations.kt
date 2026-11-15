package org.example.projectname.core.settings

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The settings document format this app writes. Raise it, and add a step, whenever the format changes. */
const val SETTINGS_VERSION: Int = 1

/** One upgrade step: takes a settings document of version N and returns it in version N + 1. */
fun interface SettingsMigration {
    fun migrate(document: JsonObject): JsonObject
}

/**
 * The registry of upgrade steps, one for each version from 1 up to [currentVersion] - 1. It refuses to
 * be built with a gap, so a missing step fails the unit tests instead of a user's settings.
 *
 * To change the format: raise [SETTINGS_VERSION], add a step to [CURRENT] from the old version, and add
 * a test with a settings file in the old format.
 */
class SettingsMigrations(
    val currentVersion: Int,
    private val steps: Map<Int, SettingsMigration>,
) {
    init {
        require(currentVersion >= 1) { "currentVersion must be at least 1, got $currentVersion" }
        val expected = (1 until currentVersion).toSet()
        require(steps.keys == expected) {
            "Settings migrations need exactly one step from each version in 1 until $currentVersion; " +
                "missing ${(expected - steps.keys).sorted()}, unexpected ${(steps.keys - expected).sorted()}"
        }
    }

    /** Upgrades [document] from [fromVersion] to [currentVersion], one step at a time. */
    fun upgrade(document: JsonObject, fromVersion: Int): JsonObject {
        require(fromVersion in 1..currentVersion) { "can't upgrade from version $fromVersion" }
        var current = document
        for (version in fromVersion until currentVersion) {
            val step = steps.getValue(version)
            current = JsonObject(step.migrate(current) + (VERSION_KEY to JsonPrimitive(version + 1)))
        }
        return current
    }

    companion object {
        const val VERSION_KEY = "version"

        /** The steps this app ships. Version 1 is the first format, so there are none yet. */
        val CURRENT: SettingsMigrations = SettingsMigrations(currentVersion = SETTINGS_VERSION, steps = emptyMap())
    }
}
