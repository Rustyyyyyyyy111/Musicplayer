package com.example.integrations

import com.example.BuildConfig
import com.example.core.model.ProxyConfig
import com.example.core.model.ProxyType
import com.example.core.model.Track
import com.example.core.util.ActivityLogger
import com.example.core.util.SecureCredentialStore
import com.example.data.local.SettingsDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.Authenticator
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Proxy
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class ScrobbleAndProxyManager(
    private val settingsDataStore: SettingsDataStore,
    private val credentialStore: SecureCredentialStore,
    private val logger: ActivityLogger
) {
    @Volatile
    private var cachedConfig: ProxyConfig = ProxyConfig()

    @Volatile
    private var cachedClient: OkHttpClient = buildClient(ProxyConfig())

    fun getHttpClient(): OkHttpClient = cachedClient

    suspend fun refreshHttpClient(): OkHttpClient {
        val config = settingsDataStore.preferencesFlow.first().proxyConfig
        if (config != cachedConfig) {
            cachedConfig = config
            cachedClient = buildClient(config)
        }
        return cachedClient
    }

    fun buildClient(config: ProxyConfig): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)

        if (config.type != ProxyType.NONE && config.host.isNotBlank() && config.port in 1..65535) {
            val javaType = when (config.type) {
                ProxyType.HTTP -> Proxy.Type.HTTP
                ProxyType.SOCKS5 -> Proxy.Type.SOCKS
                ProxyType.NONE -> Proxy.Type.DIRECT
            }
            builder.proxy(Proxy(javaType, InetSocketAddress(config.host.trim(), config.port)))
            if (config.username.isNotBlank()) {
                val credential = Credentials.basic(config.username, config.password)
                if (config.type == ProxyType.HTTP) {
                    builder.proxyAuthenticator(Authenticator { _, response ->
                        response.request.newBuilder()
                            .header("Proxy-Authorization", credential)
                            .build()
                    })
                } else {
                    java.net.Authenticator.setDefault(object : java.net.Authenticator() {
                        override fun getPasswordAuthentication(): java.net.PasswordAuthentication {
                            return java.net.PasswordAuthentication(
                                config.username,
                                config.password.toCharArray()
                            )
                        }
                    })
                }
            }
        }
        return builder.build()
    }

    suspend fun testProxyConnection(config: ProxyConfig): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            val client = buildClient(config)
            val start = System.currentTimeMillis()
            val req = Request.Builder()
                .url("https://lrclib.net/api/get?track_name=test&artist_name=test")
                .head()
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful && resp.code != 404) {
                    throw IllegalStateException("HTTP ${resp.code}")
                }
            }
            val elapsed = System.currentTimeMillis() - start
            logger.log("PROXY", "INFO", "Kiểm tra Proxy ${config.type.name} thành công (${elapsed}ms)")
            elapsed
        }.onFailure { err ->
            logger.log("PROXY", "ERROR", "Kiểm tra Proxy thất bại: ${err.message}")
        }
    }

    suspend fun scrobbleTrack(track: Track) = withContext(Dispatchers.IO) {
        val prefs = settingsDataStore.preferencesFlow.first()
        if (prefs.listenBrainzEnabled) {
            submitListenBrainz(track)
        }
        if (prefs.lastFmEnabled) {
            submitLastFm(track)
        }
    }

    private fun submitListenBrainz(track: Track) {
        val token = credentialStore.getSecret("listenbrainz_token")
        if (token.isBlank()) return
        runCatching {
            val payload = JSONObject().apply {
                put("listen_type", "single")
                put("payload", JSONArray().apply {
                    put(JSONObject().apply {
                        put("listened_at", System.currentTimeMillis() / 1000L)
                        put("track_metadata", JSONObject().apply {
                            put("artist_name", track.artist)
                            put("track_name", track.title)
                            put("release_name", track.album)
                        })
                    })
                })
            }
            val req = Request.Builder()
                .url("https://api.listenbrainz.org/1/submit-listens")
                .header("Authorization", "Token $token")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()
            cachedClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    logger.log("SCROBBLE", "INFO", "Đã scrobble ListenBrainz: ${track.title}")
                }
            }
        }.onFailure { err ->
            logger.log("SCROBBLE", "WARN", "ListenBrainz scrobble lỗi: ${err.message}")
        }
    }

    private fun submitLastFm(track: Track) {
        val apiKey = BuildConfig.LASTFM_API_KEY
        val apiSecret = BuildConfig.LASTFM_API_SECRET
        val sessionKey = credentialStore.getSecret("lastfm_session_key")
        if (apiKey.isBlank() || apiKey == "YOUR_LASTFM_API_KEY" || sessionKey.isBlank()) return

        runCatching {
            val timestamp = (System.currentTimeMillis() / 1000L).toString()
            val params = sortedMapOf(
                "api_key" to apiKey,
                "artist" to track.artist,
                "method" to "track.scrobble",
                "sk" to sessionKey,
                "timestamp" to timestamp,
                "track" to track.title
            )
            val sigRaw = params.entries.joinToString("") { "${it.key}${it.value}" } + apiSecret
            val apiSig = MessageDigest.getInstance("MD5")
                .digest(sigRaw.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

            val formBody = params.entries.joinToString("&") {
                "${java.net.URLEncoder.encode(it.key, "UTF-8")}=${java.net.URLEncoder.encode(it.value, "UTF-8")}"
            } + "&api_sig=$apiSig&format=json"

            val req = Request.Builder()
                .url("https://ws.audioscrobbler.com/2.0/")
                .post(formBody.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
                .build()
            cachedClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    logger.log("SCROBBLE", "INFO", "Đã scrobble Last.fm: ${track.title}")
                }
            }
        }.onFailure { err ->
            logger.log("SCROBBLE", "WARN", "Last.fm scrobble lỗi: ${err.message}")
        }
    }
}
