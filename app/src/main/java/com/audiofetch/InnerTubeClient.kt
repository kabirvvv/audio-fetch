package com.audiofetch

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object InnerTubeClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private const val INNERTUBE_KEY = "AIzaSyC9XL3ZjWddXya6X74dJoCTL-NKNELL6TV"
    private const val BROWSE_URL =
        "https://music.youtube.com/youtubei/v1/browse?key=$INNERTUBE_KEY&prettyPrint=false"
    private const val NEXT_URL =
        "https://music.youtube.com/youtubei/v1/next?key=$INNERTUBE_KEY&prettyPrint=false"

    // ── Base context payload ──────────────────────────────────────────────────

    private fun baseContext(cookies: String? = null): JSONObject {
        return JSONObject().apply {
            put("context", JSONObject().apply {
                put("client", JSONObject().apply {
                    put("clientName", "WEB_REMIX")
                    put("clientVersion", "1.20250601.01.00")
                    put("hl", "en")
                    put("gl", "US")
                    put("userAgent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                        "AppleWebKit/537.36 (KHTML, like Gecko) " +
                        "Chrome/124.0.0.0 Safari/537.36")
                })
            })
        }
    }

    private fun buildHeaders(cookies: String? = null): Map<String, String> {
        val headers = mutableMapOf(
            "Content-Type"   to "application/json",
            "User-Agent"     to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
            "Origin"         to "https://music.youtube.com",
            "Referer"        to "https://music.youtube.com/",
            "X-YouTube-Client-Name"    to "67",
            "X-YouTube-Client-Version" to "1.20250601.01.00",
        )
        if (!cookies.isNullOrEmpty()) {
            headers["Cookie"] = cookies
            // Extract SAPISID for auth hash
          val sapisid = cookies.split(";")
    .map { it.trim() }
    .firstOrNull { it.startsWith("SAPISID=") }
    ?.substringAfter("=")
            if (sapisid != null) {
                val timestamp = System.currentTimeMillis() / 1000
                val hash = sapisidHash(timestamp, sapisid)
                headers["Authorization"] = "SAPISIDHASH ${timestamp}_$hash"
            }
        }
        android.util.Log.d("AudioFetchAuth", "Extracted SAPISID: $sapisid")
        return headers
    }

    private fun sapisidHash(timestamp: Long, sapisid: String): String {
        val input = "$timestamp $sapisid https://music.youtube.com"
        val digest = java.security.MessageDigest.getInstance("SHA-1")
        return digest.digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    suspend fun getHome(cookies: String? = null): JSONObject = withContext(Dispatchers.IO) {
        val body = baseContext(cookies).apply {
            put("browseId", "FEmusic_home")
        }
        post(BROWSE_URL, body, cookies)
    }
    suspend fun getContinuation(continuation: String, cookies: String? = null): JSONObject =
    withContext(Dispatchers.IO) {
        val body = baseContext(cookies).apply {
            put("continuation", continuation)
        }
        post(BROWSE_URL, body, cookies)
    }

    suspend fun getWatchNext(videoId: String, cookies: String? = null): JSONObject =
        withContext(Dispatchers.IO) {
            val body = baseContext(cookies).apply {
                put("videoId", videoId)
                put("isAudioOnly", true)
            }
            post(NEXT_URL, body, cookies)
        }

    suspend fun browsePlaylist(browseId: String, cookies: String? = null): JSONObject =
        withContext(Dispatchers.IO) {
            val body = baseContext(cookies).apply {
                put("browseId", browseId)
            }
            post(BROWSE_URL, body, cookies)
        }

    private fun post(url: String, body: JSONObject, cookies: String?): JSONObject {
        val requestBody = body.toString()
            .toRequestBody("application/json".toMediaType())
        val requestBuilder = Request.Builder().url(url).post(requestBody)
        buildHeaders(cookies).forEach { (k, v) -> requestBuilder.addHeader(k, v) }
        val response = client.newCall(requestBuilder.build()).execute()
        val responseBody = response.body?.string() ?: "{}"
        return JSONObject(responseBody)
    }
}
