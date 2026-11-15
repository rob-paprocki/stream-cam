package org.example.projectname.core.profile

import kotlinx.serialization.descriptors.elementNames
import java.io.File

/** Finds the repository's shared/ folder, which holds the profile schema and its example files. */
internal object SharedFiles {
    val dir: File by lazy {
        val fromGradle = System.getProperty("project.sharedDir")?.let { File(it) }
        val candidates = listOfNotNull(fromGradle) +
            generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }.map { File(it, "shared") }
        candidates.firstOrNull { File(it, "profile.schema.json").isFile }
            ?: error("Can't find shared/profile.schema.json; looked in $candidates")
    }

    val schema: File get() = File(dir, "profile.schema.json")

    fun fixtures(kind: String): List<File> =
        File(dir, "fixtures/$kind").listFiles { file -> file.extension == "json" }.orEmpty().sortedBy { it.name }
}

/** The serial names of every destination type, read from the model (no reflection library needed). */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
internal val destinationTypeNames: Set<String> =
    Destination.serializer().descriptor.getElementDescriptor(1).elementNames.toSet()

/** The `type` value a destination is written with. */
internal fun typeName(destination: Destination): String =
    ProfileCodec.json.encodeToJsonElement(Destination.serializer(), destination)
        .let { (it as kotlinx.serialization.json.JsonObject).getValue("type") }
        .let { (it as kotlinx.serialization.json.JsonPrimitive).content }
