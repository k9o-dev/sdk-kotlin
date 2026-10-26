package dev.k9o.kibo.vault

import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import com.google.gson.reflect.TypeToken
import java.lang.reflect.Type

/**
 * Gson (de)serializer for the [SecretVisibility] discriminated union.
 *
 * Wire shape: `{"kind":"private"}` or `{"kind":"shared","principals":[...],"roles":[...]}`.
 * `principals`/`roles` are omitted from the wire when null.
 */
internal class SecretVisibilityAdapter : JsonSerializer<SecretVisibility>, JsonDeserializer<SecretVisibility> {
    private val stringListType: Type = object : TypeToken<List<String>>() {}.type

    override fun serialize(
        src: SecretVisibility,
        typeOfSrc: Type,
        context: JsonSerializationContext,
    ): JsonElement {
        val obj = JsonObject()
        when (src) {
            is SecretVisibility.Private -> obj.addProperty("kind", "private")
            is SecretVisibility.Shared -> {
                obj.addProperty("kind", "shared")
                src.principals?.let { obj.add("principals", context.serialize(it, stringListType)) }
                src.roles?.let { obj.add("roles", context.serialize(it, stringListType)) }
            }
        }
        return obj
    }

    override fun deserialize(
        json: JsonElement,
        typeOfT: Type,
        context: JsonDeserializationContext,
    ): SecretVisibility {
        val obj = json.asJsonObject
        return when (obj.get("kind")?.asString) {
            "shared" -> SecretVisibility.Shared(
                principals = obj.get("principals")?.takeUnless { it.isJsonNull }
                    ?.let { context.deserialize<List<String>>(it, stringListType) },
                roles = obj.get("roles")?.takeUnless { it.isJsonNull }
                    ?.let { context.deserialize<List<String>>(it, stringListType) },
            )
            else -> SecretVisibility.Private
        }
    }
}
