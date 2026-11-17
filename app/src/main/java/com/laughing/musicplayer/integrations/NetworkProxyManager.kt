package com.laughing.musicplayer.integrations

import com.laughing.musicplayer.core.model.ProxyConfig
import com.laughing.musicplayer.core.model.ProxyType
import com.laughing.musicplayer.core.util.ActivityLogger
import com.laughing.musicplayer.data.local.SettingsDataStore
import com.laughing.musicplayer.data.youtube.YouTubeSessionCookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.Authenticator
import okhttp3.Credentials
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

class NetworkProxyManager(
    private val settingsDataStore: SettingsDataStore,
    private val youTubeCookieManager: YouTubeSessionCookieManager,
    private val logger: ActivityLogger
) {
    @Volatile
    private var cachedConfig: ProxyConfig = ProxyConfig()

    @Volatile
    private var cachedClient: OkHttpClient = buildClient(ProxyConfig())

    @Volatile
    private var cachedStreamClient: OkHttpClient = buildStreamClient(ProxyConfig())

    fun getHttpClient(): OkHttpClient = cachedClient

    fun getStreamHttpClient(): OkHttpClient = cachedStreamClient

    fun getYouTubeCookieHeader(url: String): String? = youTubeCookieManager.getCookieHeaderForUrl(url)

    suspend fun refreshHttpClient(): OkHttpClient {
        val config = settingsDataStore.preferencesFlow.first().proxyConfig
        if (config != cachedConfig) {
            cachedConfig = config
            cachedClient = buildClient(config)
            cachedStreamClient = buildStreamClient(config)
        }
        return cachedClient
    }

    fun rebuildClients() {
        cachedClient = buildClient(cachedConfig)
        cachedStreamClient = buildStreamClient(cachedConfig)
    }

    fun buildClient(config: ProxyConfig): OkHttpClient = buildConfiguredClient(
        config = config,
        connectTimeoutSec = 10,
        readTimeoutSec = 15,
        writeTimeoutSec = 15,
        callTimeoutSec = 20
    )

    fun buildStreamClient(config: ProxyConfig): OkHttpClient = buildConfiguredClient(
        config = config,
        connectTimeoutSec = 8,
        readTimeoutSec = 9,
        writeTimeoutSec = 9,
        callTimeoutSec = 10
    )

    private fun buildConfiguredClient(
        config: ProxyConfig,
        connectTimeoutSec: Long,
        readTimeoutSec: Long,
        writeTimeoutSec: Long,
        callTimeoutSec: Long
    ): OkHttpClient {
        val youTubeCookieInterceptor = Interceptor { chain ->
            val original = chain.request()
            val urlStr = original.url.toString()
            val ytCookie = youTubeCookieManager.getCookieHeaderForUrl(urlStr)
            if (ytCookie.isNullOrBlank()) {
                chain.proceed(original)
            } else {
                val reqBuilder = original.newBuilder()
                val existingCookie = original.header("Cookie")
                val combinedCookie = if (existingCookie.isNullOrBlank()) {
                    ytCookie
                } else {
                    YouTubeSessionCookieManager.parseAndMergeCookies(existingCookie, ytCookie)
                        .entries.joinToString("; ") { "${it.key}=${it.value}" }
                }
                reqBuilder.header("Cookie", combinedCookie)
                if (original.header("User-Agent").isNullOrBlank()) {
                    reqBuilder.header("User-Agent", YouTubeSessionCookieManager.DEFAULT_USER_AGENT)
                }
                chain.proceed(reqBuilder.build())
            }
        }

        val builder = OkHttpClient.Builder()
            .addInterceptor(youTubeCookieInterceptor)
            .connectTimeout(connectTimeoutSec, TimeUnit.SECONDS)
            .readTimeout(readTimeoutSec, TimeUnit.SECONDS)
            .writeTimeout(writeTimeoutSec, TimeUnit.SECONDS)
            .callTimeout(callTimeoutSec, TimeUnit.SECONDS)

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
            val client = buildStreamClient(config)
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
}
