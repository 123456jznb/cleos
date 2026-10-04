package com.cleo.cleos.ai

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.util.Collections

/**
 * The second try at an address that only looks wrong.
 *
 * A user of a relay was told "地址能连上，但回的不是 JSON，多半不是聊天接口" and had no way to
 * know the answer was `/v1`: what she had typed was the host the relay's front page lives on,
 * and that page answers *every* path under it — `POST /chat/completions` included — with its
 * own HTML under HTTP 200. So the app tries again one level down, but only when the failure
 * says the address is the thing at fault: a refused key or an empty balance comes from an
 * endpoint that was found, and moving the path under it would hide the real problem instead
 * of reporting it.
 */
class AddressFallbackTest {

    private fun endpoint(written: String) = ApiEndpoint(written, "key", "glm-4.7")

    @Test
    fun `an address with no version segment gets one put underneath`() {
        assertEquals(
            "https://stable.monkeyapi.net/v1",
            endpoint("https://stable.monkeyapi.net/").withV1?.baseUrl,
        )
        assertEquals("https://api.deepseek.com/v1", endpoint("https://api.deepseek.com").withV1?.baseUrl)
        // A port is not a path segment.
        assertEquals("https://x.com:8443/v1", endpoint("https://x.com:8443").withV1?.baseUrl)
        // Whatever path it does have is kept: this is one level down, not a rewrite.
        assertEquals("https://x.com/openai/v1", endpoint("https://x.com/openai").withV1?.baseUrl)
    }

    @Test
    fun `an address that already names a version is left alone`() {
        for (written in listOf(
            "https://open.bigmodel.cn/api/paas/v4",
            "https://api.openai.com/v1",
            "https://relay.example.com/v1/",
            "https://relay.example.com/v1/chat/completions",
            "https://relay.example.com/v1beta",
            "https://relay.example.com/V1",
        )) {
            assertNull(written, endpoint(written).withV1)
        }
    }

    @Test
    fun `the address as typed is tried first, the fallback only when the address is what failed`() = runBlocking {
        val tried = mutableListOf<String>()
        val remembered = HashMap<String, String>()
        val answer = atRightAddress(endpoint("https://relay.example.com"), remembered) { to ->
            tried += to.chatUrl
            if (tried.size == 1) throw ChatException("回的不是 JSON", wrongEndpoint = true)
            "连上了"
        }
        assertEquals("连上了", answer)
        assertEquals(
            listOf(
                "https://relay.example.com/chat/completions",
                "https://relay.example.com/v1/chat/completions",
            ),
            tried,
        )
        assertEquals("https://relay.example.com/v1", remembered["https://relay.example.com"])
    }

    @Test
    fun `the address that worked is remembered, so the next message goes straight there`() = runBlocking {
        val remembered = HashMap<String, String>()
        val tried = mutableListOf<String>()
        val talk: suspend (ApiEndpoint) -> String = { to ->
            tried += to.chatUrl
            if (!to.baseUrl.endsWith("/v1")) throw ChatException("回的不是 JSON", wrongEndpoint = true)
            "连上了"
        }
        atRightAddress(endpoint("https://relay.example.com"), remembered, talk)
        atRightAddress(endpoint("https://relay.example.com"), remembered, talk)
        assertEquals(
            listOf(
                "https://relay.example.com/chat/completions",
                "https://relay.example.com/v1/chat/completions",
                // The second message pays for nothing: one request, at the address that answers.
                "https://relay.example.com/v1/chat/completions",
            ),
            tried,
        )
    }

    @Test
    fun `a refused key is never answered by moving the path`() = runBlocking {
        val tried = mutableListOf<String>()
        val refused = runCatching {
            atRightAddress(endpoint("https://relay.example.com"), HashMap()) { to ->
                tried += to.chatUrl
                throw ChatException("API Key 不对，或者已经失效了", 401)
            }
        }.exceptionOrNull() as ChatException
        assertEquals("API Key 不对，或者已经失效了", refused.message)
        assertEquals(listOf("https://relay.example.com/chat/completions"), tried)
    }

    @Test
    fun `when neither address answers, the one complained about is the lower of the two`() = runBlocking {
        val tried = mutableListOf<String>()
        val failed = runCatching {
            atRightAddress(endpoint("https://relay.example.com"), HashMap()) { to ->
                tried += to.chatUrl
                throw ChatException("回的不是 JSON：" + to.chatUrl, wrongEndpoint = true)
            }
        }.exceptionOrNull() as ChatException
        assertEquals(2, tried.size)
        // Both are pages, so neither says more than the other — but /v1 is where the app ended
        // up, and it is that address, not the one abandoned on the way, that is being reported.
        assertTrue(
            "${failed.message}",
            failed.message!!.endsWith("https://relay.example.com/v1/chat/completions"),
        )
    }

    @Test
    fun `when the fallback answers with a complaint of its own, that is the one reported`() = runBlocking {
        val remembered = HashMap<String, String>()
        val failed = runCatching {
            atRightAddress(endpoint("https://relay.example.com"), remembered) { to ->
                if (to.baseUrl.endsWith("/v1")) throw ChatException("账户余额不足", 402)
                throw ChatException("回的不是 JSON", wrongEndpoint = true)
            }
        }.exceptionOrNull() as ChatException
        assertEquals("账户余额不足", failed.message)
        // That address is the endpoint after all: later requests go straight to it.
        assertEquals("https://relay.example.com/v1", remembered["https://relay.example.com"])
    }

    @Test
    fun `a page where a reply should be is the address's fault, a complaint is not`() {
        val page = runCatching { StreamParser().wholeReply("<html><body>Just a moment...</body></html>") }
            .exceptionOrNull() as ChatException
        assertTrue(page.wrongEndpoint)
        val empty = runCatching { StreamParser().wholeReply("  \n") }.exceptionOrNull() as ChatException
        assertTrue(empty.wrongEndpoint)
        // The service answered as itself: it is found, so this is about the key or the model.
        val complained = runCatching { StreamParser().feed("""{"error":{"message":"context too long"}}""") }
            .exceptionOrNull() as ChatException
        assertFalse(complained.wrongEndpoint)
    }

    @Test
    fun `when both addresses are wrong, the one that got words out of a service is reported`() = runBlocking {
        // A relay whose front page answers everything, and whose /v1 is the real API: there it
        // is a model name the API has never heard of. Reporting the page above it ("回的不是
        // JSON") sends the person to check an address that was right all along.
        val failed = runCatching {
            atRightAddress(endpoint("https://relay.example.com"), HashMap()) { to ->
                if (!to.baseUrl.endsWith("/v1")) throw ChatException("地址能连上，但回的不是 JSON。", wrongEndpoint = true)
                throw ChatException(
                    "地址能连上，但它没有按聊天接口回话：no such model: claude-x",
                    wrongEndpoint = true,
                    serviceSpoke = true,
                )
            }
        }.exceptionOrNull() as ChatException
        assertTrue("${failed.message}", failed.message!!.contains("no such model"))
    }

    @Test
    fun `when it is the address typed that spoke, its words are the ones kept`() = runBlocking {
        val failed = runCatching {
            atRightAddress(endpoint("https://relay.example.com"), HashMap()) { to ->
                if (to.baseUrl.endsWith("/v1")) throw ChatException("地址能连上，但回的不是 JSON。", wrongEndpoint = true)
                throw ChatException("没有这个模型：claude-x", wrongEndpoint = true, serviceSpoke = true)
            }
        }.exceptionOrNull() as ChatException
        assertEquals("没有这个模型：claude-x", failed.message)
    }

    @Test
    fun `an address nobody has typed a host into gets no second try`() {
        assertNull(endpoint("").withV1)
        assertNull(endpoint("   ").withV1)
        // No scheme: OkHttp would not take it either, so there is nothing to hang /v1 under.
        assertNull(endpoint("monkeyapi.net").withV1)
    }

    @Test
    fun `the voice too is asked again a level down`() = runBlocking {
        val tried = mutableListOf<String>()
        val say: suspend (ApiEndpoint) -> String = { to ->
            tried += Speech.speechUrl(to.baseUrl)
            if (tried.size == 1) throw SpeechException("回来的不是声音：<html>…</html>", wrongEndpoint = true)
            "声音"
        }
        assertEquals("声音", atRightAddress(endpoint("https://relay.example.com"), HashMap(), say))
        assertEquals(
            listOf(
                "https://relay.example.com/audio/speech",
                "https://relay.example.com/v1/audio/speech",
            ),
            tried,
        )
    }

    @Test
    fun `a transcription is asked for at the same two addresses`() = runBlocking {
        val tried = mutableListOf<String>()
        val hear: suspend (ApiEndpoint) -> String = { to ->
            tried += Voice.url(to.baseUrl)
            if (tried.size == 1) throw ChatException("服务回的不是转写结果：<html>…</html>", wrongEndpoint = true)
            "听到了"
        }
        assertEquals("听到了", atRightAddress(endpoint("https://relay.example.com"), HashMap(), hear))
        assertEquals(
            listOf(
                "https://relay.example.com/audio/transcriptions",
                "https://relay.example.com/v1/audio/transcriptions",
            ),
            tried,
        )
    }

    @Test
    fun `a voice service that turns the key down is not asked again somewhere else`() = runBlocking {
        val tried = mutableListOf<String>()
        val failed = runCatching {
            atRightAddress(endpoint("https://relay.example.com"), HashMap()) { to ->
                tried += Speech.speechUrl(to.baseUrl)
                throw SpeechException("Key 不对，或者已经失效了", wrongEndpoint = false)
            }
        }.exceptionOrNull() as SpeechException
        assertEquals("Key 不对，或者已经失效了", failed.message)
        assertEquals(listOf("https://relay.example.com/audio/speech"), tried)
    }

    @Test
    fun `what the chat learned, the voice does not pay for again`() = runBlocking {
        // A host of its own: the learned addresses are shared by the whole app for the run
        // (AddressMemory), which is the point — the voice goes to the address the chat is set to.
        val typed = endpoint("https://learned-${System.nanoTime()}.example.com")
        val tried = mutableListOf<String>()
        val chat: suspend (ApiEndpoint) -> String = { to ->
            tried += to.chatUrl
            if (!to.baseUrl.endsWith("/v1")) throw ChatException("回的不是 JSON", wrongEndpoint = true)
            "连上了"
        }
        atRightAddress(typed, attempt = chat)
        atRightAddress(typed) { to ->
            tried += Speech.speechUrl(to.baseUrl)
            "声音"
        }
        assertEquals(
            listOf(
                typed.chatUrl,
                "${typed.baseUrl}/v1/chat/completions",
                "${typed.baseUrl}/v1/audio/speech",
            ),
            tried,
        )
    }
}

/**
 * The same thing end to end, against a real server on the loopback: `probe` is the connection
 * test the settings screen runs, so it is the road that has to be walked.
 */
class AddressFallbackOverHttpTest {

    private val client = ChatClient(OkHttpClient())

    /** A server that answers what [reply] says it answers. */
    private fun server(reply: (String) -> Pair<Int, String>): HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                val (code, body) = reply(exchange.requestURI.path)
                val bytes = body.toByteArray()
                exchange.sendResponseHeaders(code, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }

    @Test
    fun `the connection test finds the API behind the page the address was copied from`() = runBlocking {
        val seen = Collections.synchronizedList(mutableListOf<String>())
        val relay = server { path ->
            seen += path
            // What a relay's front page does with anything you ask it, including this.
            if (path == "/v1/chat/completions") {
                200 to """{"choices":[{"message":{"role":"assistant","content":"hi"}}]}"""
            } else {
                200 to "<!doctype html><html><body>Just a moment...</body></html>"
            }
        }
        try {
            val typed = endpoint(relay.address.port)
            client.probe(typed)
            assertEquals(listOf("/chat/completions", "/v1/chat/completions"), seen)

            // Tested a second time, it goes straight to the address that answered.
            client.probe(typed)
            assertEquals(
                listOf("/chat/completions", "/v1/chat/completions", "/v1/chat/completions"),
                seen,
            )
        } finally {
            relay.stop(0)
        }
    }

    @Test
    fun `a service that refuses the key is not asked again somewhere else`() = runBlocking {
        val seen = Collections.synchronizedList(mutableListOf<String>())
        val api = server { path ->
            seen += path
            401 to """{"error":{"message":"invalid api key"}}"""
        }
        try {
            val failed = runCatching { client.probe(endpoint(api.address.port)) }
                .exceptionOrNull() as ChatException
            assertTrue("${failed.message}", failed.message!!.contains("API Key"))
            assertEquals(listOf("/chat/completions"), seen)
        } finally {
            api.stop(0)
        }
    }

    @Test
    fun `a model the relay behind the page does not know is reported as that, not as a bad address`() = runBlocking {
        val relay = server { path ->
            // Exactly what stable.monkeyapi.net does: the SPA answers any path with itself,
            // and /v1 is the API — which here says the model name is not one of its own.
            if (path == "/v1/chat/completions") {
                200 to """{"error":{"message":"no such model: Calude-sonnet-4.6"}}"""
            } else {
                200 to "<!doctype html><html><body>Just a moment...</body></html>"
            }
        }
        try {
            val failed = runCatching { client.probe(endpoint(relay.address.port)) }
                .exceptionOrNull() as ChatException
            assertTrue("${failed.message}", failed.message!!.contains("no such model"))
        } finally {
            relay.stop(0)
        }
    }

    @Test
    fun `a 404 carrying the service's own complaint is the one reported`() = runBlocking {
        val api = server { path ->
            if (path == "/v1/chat/completions") {
                404 to """{"error":{"message":"model_not_found"}}"""
            } else {
                200 to "<!doctype html><html><body>Just a moment...</body></html>"
            }
        }
        try {
            val failed = runCatching { client.probe(endpoint(api.address.port)) }
                .exceptionOrNull() as ChatException
            assertTrue("${failed.message}", failed.message!!.contains("model_not_found"))
        } finally {
            api.stop(0)
        }
    }

    private fun endpoint(port: Int) = ApiEndpoint("http://127.0.0.1:$port", "key", "glm-4.7")
}
