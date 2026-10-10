package com.example.touchgrass.features.focus.insights

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** Productive and off-task foreground time recorded during a focus session. */
data class SessionAppUsage(
    val apps: Map<String, Int> = emptyMap(),
    val offTask: Map<String, Int> = emptyMap()
) {
    val appsSec: Int
        get() = apps.values.sum()

    val offTaskSec: Int
        get() = offTask.values.sum()

    val isEmpty: Boolean
        get() = apps.isEmpty() && offTask.isEmpty()

    fun toJson(): String = buildJsonObject {
        put("apps", apps.toJsonObject())
        put("offTask", offTask.toJsonObject())
    }.toString()

    companion object {
        val EMPTY: SessionAppUsage = SessionAppUsage()

        fun fromJson(json: String): SessionAppUsage {
            if (json.isBlank()) return EMPTY
            return runCatching { fromJsonObject(Json.parseToJsonElement(json).jsonObject) }
                .getOrDefault(EMPTY)
        }

        fun fromJsonObject(obj: JsonObject?): SessionAppUsage {
            if (obj == null) return EMPTY
            val apps = obj["apps"].positiveSeconds()
            val offTask = obj["offTask"].positiveSeconds()
            return if (apps.isEmpty() && offTask.isEmpty()) EMPTY else SessionAppUsage(apps, offTask)
        }

        private fun JsonElement?.positiveSeconds(): Map<String, Int> {
            val entries = (this as? JsonObject)?.mapNotNull { (packageName, value) ->
                val primitive = value as? JsonPrimitive
                val seconds = primitive
                    ?.takeUnless { it.isString }
                    ?.content
                    ?.toDoubleOrNull()
                    ?.toInt()
                if (seconds != null && seconds > 0) packageName to seconds else null
            }.orEmpty()
            return entries.toMap()
        }

        private fun Map<String, Int>.toJsonObject(): JsonObject = buildJsonObject {
            forEach { (packageName, seconds) -> put(packageName, seconds) }
        }
    }
}
