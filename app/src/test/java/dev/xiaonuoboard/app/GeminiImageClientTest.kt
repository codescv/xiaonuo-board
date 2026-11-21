package dev.xiaonuoboard.app

import dev.xiaonuoboard.app.ai.GeminiConfig
import dev.xiaonuoboard.app.ai.GeminiException
import dev.xiaonuoboard.app.ai.GeminiImageClient
import dev.xiaonuoboard.app.ai.GeminiModels
import dev.xiaonuoboard.app.ai.InlineImage
import dev.xiaonuoboard.app.ai.PromptBuilder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.Base64

class GeminiImageClientTest {
    private lateinit var server: MockWebServer
    private val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 1, 2, 3)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun imageResponse(bytes: ByteArray = png, text: String = "here you go") = MockResponse().setResponseCode(200).setBody(
        """{"candidates":[{"content":{"parts":[{"text":"$text"},{"inlineData":{"mimeType":"image/png","data":"${Base64.getEncoder().encodeToString(bytes)}"}}]}}]}""",
    )

    private fun client(model: String = GeminiModels.DEFAULT, base: String = server.url("/").toString()) =
        GeminiImageClient({ GeminiConfig(apiKey = "test-key", baseUrl = base, model = model) })

    @Test
    fun defaultModelIsNanoBanana21() {
        assertEquals("gemini-nano-banana-2.1", GeminiModels.DEFAULT)
        assertEquals("gemini-nano-banana-2.1", GeminiConfig(apiKey = "k").model)
        assertEquals(GeminiModels.DEFAULT, GeminiModels.OPTIONS.first().id)
        val ids = GeminiModels.OPTIONS.map { it.id }
        assertTrue(ids.containsAll(listOf("gemini-3-pro-image", "gemini-3.1-flash-lite-image")))
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-nano-banana-2.1:generateContent",
            GeminiConfig(apiKey = "k").generateContentUrl(),
        )
    }

    @Test
    fun postsGenerateContentWithKeyHeaderModalitiesAndInlineImage() {
        server.enqueue(imageResponse())
        val result = client().generate("draw a cat", listOf(InlineImage("image/png", png)))
        assertArrayEquals(png, result.firstImage.bytes)
        assertEquals("here you go", result.text)

        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/v1beta/models/gemini-nano-banana-2.1:generateContent", req.path)
        assertEquals("test-key", req.getHeader("x-goog-api-key"))
        val body = Json.parseToJsonElement(req.body.readUtf8()).jsonObject
        val modalities = body["generationConfig"]!!.jsonObject["responseModalities"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("IMAGE", "TEXT"), modalities)
        val parts = body["contents"]!!.jsonArray[0].jsonObject["parts"]!!.jsonArray
        assertEquals(Base64.getEncoder().encodeToString(png), parts[0].jsonObject["inline_data"]!!.jsonObject["data"]!!.jsonPrimitive.content)
        assertEquals("draw a cat", parts[1].jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun customBaseUrlProxyAndModelAreHonoured() {
        server.enqueue(imageResponse())
        val base = server.url("/proxy/gemini/v1beta/").toString() // trailing /v1beta and slash are normalized
        client(model = "models/gemini-3-pro-image", base = base).generate("x")
        val req = server.takeRequest()
        assertEquals("/proxy/gemini/v1beta/models/gemini-3-pro-image:generateContent", req.path)
    }

    @Test
    fun blankBaseUrlFallsBackToDefault() {
        val cfg = GeminiConfig(apiKey = "k", baseUrl = "  ", model = " ")
        assertEquals(GeminiModels.DEFAULT_BASE_URL, cfg.normalizedBaseUrl)
        assertEquals(GeminiModels.DEFAULT, cfg.normalizedModel)
    }

    @Test
    fun httpErrorsSurfaceReadableMessages() {
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":{"code":403,"message":"API key not valid"}}"""))
        try {
            client().generate("x")
            fail("expected GeminiException")
        } catch (e: GeminiException) {
            assertEquals(403, e.httpCode)
            assertTrue(e.message!!.contains("API Key"))
            assertTrue(e.message!!.contains("API key not valid"))
        }
    }

    @Test
    fun textOnlyResponseHasNoImage() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"candidates":[{"content":{"parts":[{"text":"I cannot draw that"}]}}]}"""))
        val result = client().generate("x")
        try {
            result.firstImage
            fail("expected GeminiException")
        } catch (e: GeminiException) {
            assertTrue(e.message!!.contains("I cannot draw that"))
        }
    }

    @Test
    fun blockedPromptIsReported() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"promptFeedback":{"blockReason":"SAFETY"}}"""))
        try {
            client().generate("x"); fail()
        } catch (e: GeminiException) {
            assertTrue(e.message!!.contains("SAFETY"))
        }
    }

    @Test
    fun missingApiKeyFailsFastWithoutNetwork() {
        val c = GeminiImageClient({ GeminiConfig(apiKey = "", baseUrl = server.url("/").toString()) })
        try {
            c.generate("x"); fail()
        } catch (e: GeminiException) {
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun malformedJsonIsAnError() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html>proxy error</html>"))
        try {
            client().generate("x"); fail()
        } catch (e: GeminiException) {
            assertTrue(e.message!!.isNotBlank())
        }
    }

    @Test
    fun connectivityTestHitsModelEndpoint() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"name":"models/gemini-nano-banana-2.1","displayName":"Nano Banana 2.1"}"""))
        val msg = client().testConnectivity()
        assertTrue(msg.contains("Nano Banana 2.1"))
        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertEquals("/v1beta/models/gemini-nano-banana-2.1", req.path)
        assertEquals("test-key", req.getHeader("x-goog-api-key"))
    }

    @Test
    fun connectivityFailureOn404() {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":{"message":"model not found"}}"""))
        try {
            client().testConnectivity(); fail()
        } catch (e: GeminiException) {
            assertEquals(404, e.httpCode)
        }
    }

    @Test
    fun malformedBaseUrlBecomesGeminiExceptionNotCrash() {
        for (bad in listOf("generativelanguage.googleapis.com", "ht tp://bad host", "ftp://x")) {
            val c = GeminiImageClient({ GeminiConfig(apiKey = "k", baseUrl = bad) })
            try {
                c.generate("x"); fail("expected GeminiException for '$bad'")
            } catch (e: GeminiException) {
                assertTrue(e.message!!.contains("Base URL"))
            }
            try {
                c.testConnectivity(); fail()
            } catch (e: GeminiException) {
                assertTrue(e.message!!.contains("Base URL"))
            }
        }
    }

    @Test
    fun apiKeyWhitespaceAndNewlinesAreStripped() {
        server.enqueue(imageResponse())
        GeminiImageClient({ GeminiConfig(apiKey = " test-\nkey\r\n", baseUrl = server.url("/").toString()) }).generate("x")
        assertEquals("test-key", server.takeRequest().getHeader("x-goog-api-key"))
    }

    @Test
    fun nonAsciiApiKeyBecomesGeminiException() {
        val c = GeminiImageClient({ GeminiConfig(apiKey = "密钥", baseUrl = server.url("/").toString()) })
        try {
            c.generate("x"); fail()
        } catch (e: GeminiException) {
            assertTrue(e.message!!.contains("API Key"))
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun mimeWrappedBase64FromProxyDecodes() {
        val bytes = ByteArray(300) { it.toByte() }
        val wrapped = Base64.getMimeEncoder().encodeToString(bytes).replace("\r\n", "\\r\\n")
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"candidates":[{"content":{"parts":[{"inline_data":{"mime_type":"image/png","data":"$wrapped"}}]}}]}""",
            ),
        )
        assertArrayEquals(bytes, client().generate("x").firstImage.bytes)
    }

    @Test
    fun corruptBase64IsGeminiException() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"candidates":[{"content":{"parts":[{"inlineData":{"mimeType":"image/png","data":"@@@###"}}]}}]}""",
            ),
        )
        try {
            client().generate("x"); fail()
        } catch (e: GeminiException) {
            assertTrue(e.message!!.isNotBlank())
        }
    }

    @Test
    fun promptsDemandClosedPureBlackLines() {
        val p = PromptBuilder.combo(PromptBuilder.CHARACTERS[0], PromptBuilder.SCENES[0], PromptBuilder.Difficulty.EASY)
        assertTrue(p.contains("closed contour"))
        assertTrue(p.contains("NO gray"))
        val a = PromptBuilder.alphabet('q', "queen's crown")
        assertTrue(a.contains("'Q'") && a.contains("'q'"))
        assertEquals(26, PromptBuilder.ALPHABET_OBJECTS.size)
    }
}
