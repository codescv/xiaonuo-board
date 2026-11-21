package dev.xiaonuoboard.app.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit

/** Selectable image models (Parent Settings). The first entry is the default. */
object GeminiModels {
    const val DEFAULT = "gemini-nano-banana-2.1"
    const val DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com"

    data class Option(val id: String, val label: String)

    val OPTIONS = listOf(
        Option(DEFAULT, "Nano Banana 2.1（默认）"),
        Option("gemini-3-pro-image", "Nano Banana Pro"),
        Option("gemini-3.1-flash-lite-image", "Nano Banana 2 Lite"),
    )
}

data class GeminiConfig(
    val apiKey: String,
    val baseUrl: String = GeminiModels.DEFAULT_BASE_URL,
    val model: String = GeminiModels.DEFAULT,
) {
    /** Normalized base URL: blank → default, trailing slashes and a trailing `/v1beta` removed. */
    val normalizedBaseUrl: String
        get() {
            var b = baseUrl.trim().ifBlank { GeminiModels.DEFAULT_BASE_URL }.trimEnd('/')
            if (b.endsWith("/v1beta")) b = b.removeSuffix("/v1beta")
            return b
        }

    val normalizedModel: String get() = model.trim().removePrefix("models/").ifBlank { GeminiModels.DEFAULT }

    fun generateContentUrl(): String = "$normalizedBaseUrl/v1beta/models/$normalizedModel:generateContent"
}

class GeminiException(message: String, val httpCode: Int = 0, cause: Throwable? = null) : IOException(message, cause)

/** One inline image passed to / returned from the model. */
class InlineImage(val mimeType: String, val bytes: ByteArray)

data class GeminiResult(val images: List<InlineImage>, val text: String) {
    val firstImage: InlineImage get() = images.firstOrNull() ?: throw GeminiException("模型未返回图片：${text.take(200)}")
}

/**
 * Minimal direct-HTTP Gemini image client:
 * `POST {baseUrl}/v1beta/models/{model}:generateContent` with header `x-goog-api-key`,
 * `generationConfig.responseModalities = ["IMAGE","TEXT"]`. Works with custom reverse proxies.
 */
class GeminiImageClient(
    private val configProvider: () -> GeminiConfig,
    private val http: OkHttpClient = defaultHttp(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun buildRequestBody(prompt: String, images: List<InlineImage>, aspectRatio: String? = "3:4"): String {
        val body = buildJsonObject {
            putJsonArray("contents") {
                add(buildJsonObject {
                    put("role", "user")
                    putJsonArray("parts") {
                        for (img in images) {
                            add(buildJsonObject {
                                putJsonObject("inline_data") {
                                    put("mime_type", img.mimeType)
                                    put("data", Base64.getEncoder().encodeToString(img.bytes))
                                }
                            })
                        }
                        add(buildJsonObject { put("text", prompt) })
                    }
                })
            }
            putJsonObject("generationConfig") {
                put("responseModalities", buildJsonArray { add(JsonPrimitive("IMAGE")); add(JsonPrimitive("TEXT")) })
                if (aspectRatio != null) {
                    putJsonObject("imageConfig") { put("aspectRatio", aspectRatio) }
                }
            }
        }
        return body.toString()
    }

    /** Synchronous call; run on a background dispatcher. */
    fun generate(prompt: String, images: List<InlineImage> = emptyList(), aspectRatio: String? = "3:4"): GeminiResult {
        val cfg = configProvider()
        if (cfg.apiKey.isBlank()) throw GeminiException("请先在家长设置中填写 Gemini API Key")
        val request = Request.Builder()
            .url(cfg.generateContentUrl())
            .header("x-goog-api-key", cfg.apiKey.trim())
            .header("Content-Type", "application/json")
            .post(buildRequestBody(prompt, images, aspectRatio).toRequestBody(JSON_MEDIA))
            .build()
        val response = try {
            http.newCall(request).execute()
        } catch (e: IOException) {
            throw GeminiException("网络连接失败：${e.message}", cause = e)
        }
        response.use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw GeminiException(errorMessage(resp.code, text), resp.code)
            return parseResponse(text)
        }
    }

    fun parseResponse(text: String): GeminiResult {
        val root = try {
            json.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            throw GeminiException("无法解析模型响应", cause = e)
        }
        (root["promptFeedback"] as? JsonObject)?.get("blockReason")?.jsonPrimitive?.contentOrNull?.let {
            throw GeminiException("请求被安全策略拦截：$it")
        }
        val candidates = root["candidates"] as? JsonArray ?: throw GeminiException("模型响应缺少 candidates")
        val images = ArrayList<InlineImage>()
        val sb = StringBuilder()
        for (cand in candidates) {
            val parts = (cand.jsonObject["content"] as? JsonObject)?.get("parts") as? JsonArray ?: continue
            for (part in parts) {
                val p = part.jsonObject
                val inline = (p["inlineData"] ?: p["inline_data"]) as? JsonObject
                if (inline != null) {
                    val data = inline["data"]?.jsonPrimitive?.contentOrNull ?: continue
                    val mime = (inline["mimeType"] ?: inline["mime_type"])?.jsonPrimitive?.contentOrNull ?: "image/png"
                    images += InlineImage(mime, Base64.getDecoder().decode(data))
                }
                p["text"]?.jsonPrimitive?.contentOrNull?.let { sb.append(it) }
            }
        }
        return GeminiResult(images, sb.toString())
    }

    /**
     * Connectivity check: verifies key + base URL + model by calling `GET {base}/v1beta/models/{model}`.
     * Returns a human-readable status line.
     */
    fun testConnectivity(): String {
        val cfg = configProvider()
        if (cfg.apiKey.isBlank()) throw GeminiException("请先填写 API Key")
        val request = Request.Builder()
            .url("${cfg.normalizedBaseUrl}/v1beta/models/${cfg.normalizedModel}")
            .header("x-goog-api-key", cfg.apiKey.trim())
            .get()
            .build()
        val response = try {
            http.newCall(request).execute()
        } catch (e: IOException) {
            throw GeminiException("网络连接失败：${e.message}", cause = e)
        }
        response.use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw GeminiException(errorMessage(resp.code, text), resp.code)
            val name = runCatching {
                json.parseToJsonElement(text).jsonObject["displayName"]?.jsonPrimitive?.contentOrNull
            }.getOrNull()
            return "连接成功 ✓ ${name ?: cfg.normalizedModel}"
        }
    }

    private fun errorMessage(code: Int, body: String): String {
        val detail = runCatching {
            json.parseToJsonElement(body).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
        }.getOrNull()
        val hint = when (code) {
            400 -> "请求参数错误"
            401, 403 -> "API Key 无效或无权限"
            404 -> "模型不存在或 Base URL 错误"
            429 -> "调用过于频繁或配额不足"
            in 500..599 -> "服务暂时不可用"
            else -> "请求失败"
        }
        return "$hint (HTTP $code)" + (detail?.let { "：$it" } ?: "")
    }

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        fun defaultHttp(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}
