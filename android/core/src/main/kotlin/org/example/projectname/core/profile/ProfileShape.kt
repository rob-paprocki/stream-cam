@file:OptIn(ExperimentalSerializationApi::class)

package org.example.projectname.core.profile

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.elementDescriptors
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import org.example.projectname.core.validation.FieldError

/**
 * Checks that a JSON tree has the shape of a model class before it is decoded, so a bad file gets
 * plain-words errors ("The file is missing destination.host.") instead of a decoder exception.
 *
 * It walks the model's serial descriptors, so it follows the Kotlin classes without a second list of
 * fields. Unknown fields, explicit nulls and secret references are all refused, as the schema does.
 */
internal object ProfileShape {
    const val TYPE_KEY = "type"

    private val secretRefName = SecretRef.serializer().descriptor.serialName

    fun check(element: JsonElement, descriptor: SerialDescriptor): List<FieldError> {
        val errors = ArrayList<FieldError>()
        checkValue(element, descriptor, path = "", errors)
        return errors
    }

    /** Whether an element of a class is a [SecretRef], which a profile file may never contain. */
    fun isSecretReference(descriptor: SerialDescriptor): Boolean =
        descriptor.serialName.removeSuffix("?") == secretRefName

    private fun checkValue(element: JsonElement, descriptor: SerialDescriptor, path: String, errors: MutableList<FieldError>) {
        if (element is JsonNull) {
            errors += FieldError(path, "${quote(path)} is null. Leave the field out instead.")
            return
        }
        when (descriptor.kind) {
            PrimitiveKind.STRING ->
                if (!(element is JsonPrimitive && element.isString)) errors += FieldError(path, "${quote(path)} must be text.")
            PrimitiveKind.INT, PrimitiveKind.LONG, PrimitiveKind.SHORT, PrimitiveKind.BYTE ->
                if (!(element is JsonPrimitive && !element.isString && element.longOrNull != null)) {
                    errors += FieldError(path, "${quote(path)} must be a whole number.")
                }
            PrimitiveKind.BOOLEAN ->
                if (!(element is JsonPrimitive && !element.isString && element.booleanOrNull != null)) {
                    errors += FieldError(path, "${quote(path)} must be true or false.")
                }
            SerialKind.ENUM -> {
                val names = descriptor.elementNames.toList()
                if (!(element is JsonPrimitive && element.isString && element.content in names)) {
                    errors += FieldError(path, "${quote(path)} must be one of: ${names.joinToString(", ")}.")
                }
            }
            StructureKind.CLASS, StructureKind.OBJECT -> checkObject(element, descriptor, path, errors, extraKeys = emptySet())
            PolymorphicKind.SEALED -> checkSealed(element, descriptor, path, errors)
            else -> error("The profile model uses ${descriptor.kind}, which ProfileShape doesn't handle")
        }
    }

    private fun checkObject(
        element: JsonElement,
        descriptor: SerialDescriptor,
        path: String,
        errors: MutableList<FieldError>,
        extraKeys: Set<String>,
    ) {
        if (element !is JsonObject) {
            errors += FieldError(path, if (path.isEmpty()) "The file must hold one JSON object." else "${quote(path)} must be a JSON object.")
            return
        }
        for ((key, value) in element) {
            if (key in extraKeys) continue
            val childPath = join(path, key)
            val index = descriptor.getElementIndex(key)
            if (index == CompositeDecoder.UNKNOWN_NAME) {
                errors += FieldError(childPath, "The file has a field this app doesn't know: ${quote(childPath)}.")
                continue
            }
            val child = descriptor.getElementDescriptor(index)
            if (isSecretReference(child)) {
                errors += FieldError(
                    childPath,
                    "The file has ${quote(childPath)}, a link to a saved secret. Profile files can't carry " +
                        "secrets, so enter it again after importing.",
                )
                continue
            }
            checkValue(value, child, childPath, errors)
        }
        for (index in 0 until descriptor.elementsCount) {
            val name = descriptor.getElementName(index)
            if (!descriptor.isElementOptional(index) && name !in element) {
                errors += FieldError(join(path, name), "The file is missing ${quote(join(path, name))}.")
            }
        }
    }

    private fun checkSealed(element: JsonElement, descriptor: SerialDescriptor, path: String, errors: MutableList<FieldError>) {
        if (element !is JsonObject) {
            errors += FieldError(path, "${quote(path)} must be a JSON object.")
            return
        }
        val typePath = join(path, TYPE_KEY)
        val subclasses = descriptor.getElementDescriptor(1).elementDescriptors.toList()
        val typeNames = subclasses.joinToString(", ") { it.serialName }
        val type = element[TYPE_KEY]
        if (type == null) {
            errors += FieldError(typePath, "The file is missing ${quote(typePath)}. It must be one of: $typeNames.")
            return
        }
        val subclass = subclasses.firstOrNull { type is JsonPrimitive && type.isString && it.serialName == type.content }
        if (subclass == null) {
            errors += FieldError(typePath, "${quote(typePath)} must be one of: $typeNames.")
            return
        }
        checkObject(element, subclass, path, errors, extraKeys = setOf(TYPE_KEY))
    }

    private fun join(path: String, key: String) = if (path.isEmpty()) key else "$path.$key"

    private fun quote(path: String) = "\"$path\""
}
