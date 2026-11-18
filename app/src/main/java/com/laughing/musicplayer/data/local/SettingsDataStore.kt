package com.laughing.musicplayer.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.laughing.musicplayer.core.model.CrossfadeOption
import com.laughing.musicplayer.core.model.EqPreset
import com.laughing.musicplayer.core.model.EqState
import com.laughing.musicplayer.core.model.ProxyConfig
import com.laughing.musicplayer.core.model.ProxyType
import com.laughing.musicplayer.core.model.PublicChannelAccessMode
import com.laughing.musicplayer.core.model.RepeatMode
import com.laughing.musicplayer.core.model.ThemeMode
import com.laughing.musicplayer.core.util.SecureCredentialStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.musicPrefsDataStore: DataStore<Preferences> by preferencesDataStore(name = "music_player_prefs")

data class UserPreferences(
    val themeMode: ThemeMode = ThemeMode.LIQUID_GLASS,
    val crossfadeSeconds: Int = 2,
    val crossfadeOption: CrossfadeOption = CrossfadeOption.TWO_SECONDS,
    val autoPlayNext: Boolean = true,
    val includeVoiceMessages: Boolean = false,
    val autoSyncTelegram: Boolean = true,
    val preloadNextTrack: Boolean = true,
    val cacheLimitBytes: Long = 2L * 1024 * 1024 * 1024,
    val safTreeUri: String? = null,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val shuffleEnabled: Boolean = false,
    val eqState: EqState = EqState(),
    val proxyConfig: ProxyConfig = ProxyConfig(),
    val lastTrackId: String? = null,
    val lastPositionMs: Long = 0L,
    val activeTelegramAccountId: Int = 0,
    val showLyricsOnLockScreen: Boolean = true,
    val isYouTubeLoggedIn: Boolean = false,
    val publicChannelSyncEnabled: Boolean = false,
    val publicChannelAccessMode: PublicChannelAccessMode = PublicChannelAccessMode.BACKEND_API,
    val publicBackendBaseUrl: String = ""
)

class SettingsDataStore(
    private val context: Context,
    private val credentialStore: SecureCredentialStore
) {
    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val CROSSFADE_SEC = intPreferencesKey("crossfade_sec")
        val AUTO_PLAY_NEXT = booleanPreferencesKey("auto_play_next")
        val INCLUDE_VOICE = booleanPreferencesKey("include_voice")
        val AUTO_SYNC = booleanPreferencesKey("auto_sync")
        val PRELOAD_NEXT = booleanPreferencesKey("preload_next")
        val CACHE_LIMIT_BYTES = longPreferencesKey("cache_limit_bytes")
        val SAF_TREE_URI = stringPreferencesKey("saf_tree_uri")
        val REPEAT_MODE = stringPreferencesKey("repeat_mode")
        val SHUFFLE_ENABLED = booleanPreferencesKey("shuffle_enabled")
        val EQ_ENABLED = booleanPreferencesKey("eq_enabled")
        val EQ_PRESET = stringPreferencesKey("eq_preset")
        val EQ_BANDS = stringPreferencesKey("eq_bands")
        val EQ_PREAMP = floatPreferencesKey("eq_preamp")
        val EQ_CLIPPING_GUARD = booleanPreferencesKey("eq_clipping_guard")
        val EQ_AUTO_GENRE = booleanPreferencesKey("eq_auto_genre")
        val EQ_LOUDNESS_NORM = booleanPreferencesKey("eq_loudness_norm")
        val PROXY_TYPE = stringPreferencesKey("proxy_type")
        val PROXY_HOST = stringPreferencesKey("proxy_host")
        val PROXY_PORT = intPreferencesKey("proxy_port")
        val PROXY_USER = stringPreferencesKey("proxy_user")
        val LAST_TRACK_ID = stringPreferencesKey("last_track_id")
        val LAST_POSITION_MS = longPreferencesKey("last_position_ms")
        val ACTIVE_TG_ACCOUNT = intPreferencesKey("active_tg_account")
        val SHOW_LYRICS_LOCKSCREEN = booleanPreferencesKey("show_lyrics_lockscreen")
        val YT_LOGGED_IN = booleanPreferencesKey("yt_logged_in")
        val PUBLIC_SYNC_ENABLED = booleanPreferencesKey("public_sync_enabled")
        val PUBLIC_ACCESS_MODE = stringPreferencesKey("public_access_mode")
        val PUBLIC_BACKEND_URL = stringPreferencesKey("public_backend_url")
    }

    val preferencesFlow: Flow<UserPreferences> = context.musicPrefsDataStore.data.map { prefs ->
        val preset = prefs[Keys.EQ_PRESET]?.let {
            runCatching { EqPreset.valueOf(it) }.getOrNull()
        } ?: EqPreset.FLAT
        val bands = prefs[Keys.EQ_BANDS]?.split(",")?.mapNotNull { it.toFloatOrNull() }
            ?.takeIf { it.size == 10 } ?: preset.gainsDb
        val crossfadeSec = (prefs[Keys.CROSSFADE_SEC] ?: 2).coerceIn(0, 12)
        val crossfade = CrossfadeOption.entries.minByOrNull { kotlin.math.abs(it.seconds - crossfadeSec) }
            ?: CrossfadeOption.TWO_SECONDS

        UserPreferences(
            themeMode = prefs[Keys.THEME_MODE]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                ?: ThemeMode.LIQUID_GLASS,
            crossfadeSeconds = crossfadeSec,
            crossfadeOption = crossfade,
            autoPlayNext = prefs[Keys.AUTO_PLAY_NEXT] ?: true,
            includeVoiceMessages = prefs[Keys.INCLUDE_VOICE] ?: false,
            autoSyncTelegram = prefs[Keys.AUTO_SYNC] ?: true,
            preloadNextTrack = prefs[Keys.PRELOAD_NEXT] ?: true,
            cacheLimitBytes = prefs[Keys.CACHE_LIMIT_BYTES] ?: (2L * 1024 * 1024 * 1024),
            safTreeUri = prefs[Keys.SAF_TREE_URI],
            repeatMode = prefs[Keys.REPEAT_MODE]?.let { runCatching { RepeatMode.valueOf(it) }.getOrNull() }
                ?: RepeatMode.OFF,
            shuffleEnabled = prefs[Keys.SHUFFLE_ENABLED] ?: false,
            eqState = EqState(
                enabled = prefs[Keys.EQ_ENABLED] ?: true,
                preset = preset,
                bandGainsDb = bands,
                preampDb = prefs[Keys.EQ_PREAMP] ?: 0f,
                clippingProtection = prefs[Keys.EQ_CLIPPING_GUARD] ?: true,
                autoEqByGenre = prefs[Keys.EQ_AUTO_GENRE] ?: true,
                loudnessNormalization = prefs[Keys.EQ_LOUDNESS_NORM] ?: true
            ),
            proxyConfig = ProxyConfig(
                type = prefs[Keys.PROXY_TYPE]?.let { runCatching { ProxyType.valueOf(it) }.getOrNull() }
                    ?: ProxyType.NONE,
                host = prefs[Keys.PROXY_HOST].orEmpty(),
                port = prefs[Keys.PROXY_PORT] ?: 1080,
                username = prefs[Keys.PROXY_USER].orEmpty(),
                password = credentialStore.getSecret("proxy_password")
            ),
            lastTrackId = prefs[Keys.LAST_TRACK_ID],
            lastPositionMs = prefs[Keys.LAST_POSITION_MS] ?: 0L,
            activeTelegramAccountId = prefs[Keys.ACTIVE_TG_ACCOUNT] ?: 0,
            showLyricsOnLockScreen = prefs[Keys.SHOW_LYRICS_LOCKSCREEN] ?: true,
            isYouTubeLoggedIn = prefs[Keys.YT_LOGGED_IN] ?: false,
            publicChannelSyncEnabled = prefs[Keys.PUBLIC_SYNC_ENABLED] ?: false,
            publicChannelAccessMode = prefs[Keys.PUBLIC_ACCESS_MODE]?.let {
                runCatching { PublicChannelAccessMode.valueOf(it) }.getOrNull()
            } ?: PublicChannelAccessMode.BACKEND_API,
            publicBackendBaseUrl = prefs[Keys.PUBLIC_BACKEND_URL].orEmpty()
        )
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.musicPrefsDataStore.edit { it[Keys.THEME_MODE] = mode.name }
    }

    suspend fun setCrossfade(option: CrossfadeOption) {
        context.musicPrefsDataStore.edit { it[Keys.CROSSFADE_SEC] = option.seconds }
    }

    suspend fun setCrossfadeSeconds(seconds: Int) {
        context.musicPrefsDataStore.edit { it[Keys.CROSSFADE_SEC] = seconds.coerceIn(0, 12) }
    }

    suspend fun setAutoPlayNext(enabled: Boolean) {
        context.musicPrefsDataStore.edit { it[Keys.AUTO_PLAY_NEXT] = enabled }
    }

    suspend fun setIncludeVoiceMessages(enabled: Boolean) {
        context.musicPrefsDataStore.edit { it[Keys.INCLUDE_VOICE] = enabled }
    }

    suspend fun setAutoSyncTelegram(enabled: Boolean) {
        context.musicPrefsDataStore.edit { it[Keys.AUTO_SYNC] = enabled }
    }

    suspend fun setPreloadNextTrack(enabled: Boolean) {
        context.musicPrefsDataStore.edit { it[Keys.PRELOAD_NEXT] = enabled }
    }

    suspend fun setCacheLimitBytes(bytes: Long) {
        context.musicPrefsDataStore.edit { it[Keys.CACHE_LIMIT_BYTES] = bytes }
    }

    suspend fun setSafTreeUri(uri: String?) {
        context.musicPrefsDataStore.edit {
            if (uri == null) it.remove(Keys.SAF_TREE_URI) else it[Keys.SAF_TREE_URI] = uri
        }
    }

    suspend fun setRepeatAndShuffle(repeatMode: RepeatMode, shuffle: Boolean) {
        context.musicPrefsDataStore.edit {
            it[Keys.REPEAT_MODE] = repeatMode.name
            it[Keys.SHUFFLE_ENABLED] = shuffle
        }
    }

    suspend fun saveEqState(eq: EqState) {
        context.musicPrefsDataStore.edit {
            it[Keys.EQ_ENABLED] = eq.enabled
            it[Keys.EQ_PRESET] = eq.preset.name
            it[Keys.EQ_BANDS] = eq.bandGainsDb.joinToString(",")
            it[Keys.EQ_PREAMP] = eq.preampDb
            it[Keys.EQ_CLIPPING_GUARD] = eq.clippingProtection
            it[Keys.EQ_AUTO_GENRE] = eq.autoEqByGenre
            it[Keys.EQ_LOUDNESS_NORM] = eq.loudnessNormalization
        }
    }

    suspend fun saveProxyConfig(config: ProxyConfig) {
        credentialStore.putSecret("proxy_password", config.password)
        context.musicPrefsDataStore.edit {
            it[Keys.PROXY_TYPE] = config.type.name
            it[Keys.PROXY_HOST] = config.host
            it[Keys.PROXY_PORT] = config.port
            it[Keys.PROXY_USER] = config.username
        }
    }

    suspend fun savePlaybackCheckpoint(trackId: String?, positionMs: Long) {
        context.musicPrefsDataStore.edit {
            if (trackId != null) it[Keys.LAST_TRACK_ID] = trackId
            it[Keys.LAST_POSITION_MS] = positionMs
        }
    }

    suspend fun setActiveTelegramAccount(accountId: Int) {
        context.musicPrefsDataStore.edit { it[Keys.ACTIVE_TG_ACCOUNT] = accountId }
    }

    suspend fun setShowLyricsOnLockScreen(enabled: Boolean) {
        context.musicPrefsDataStore.edit { it[Keys.SHOW_LYRICS_LOCKSCREEN] = enabled }
    }

    suspend fun setYouTubeLoggedIn(loggedIn: Boolean) {
        context.musicPrefsDataStore.edit { it[Keys.YT_LOGGED_IN] = loggedIn }
    }

    suspend fun setPublicChannelSyncEnabled(enabled: Boolean) {
        context.musicPrefsDataStore.edit { it[Keys.PUBLIC_SYNC_ENABLED] = enabled }
    }

    suspend fun setPublicChannelAccessMode(mode: PublicChannelAccessMode) {
        context.musicPrefsDataStore.edit { it[Keys.PUBLIC_ACCESS_MODE] = mode.name }
    }

    suspend fun savePublicBackendConfig(baseUrl: String, bearerToken: String?) {
        context.musicPrefsDataStore.edit {
            it[Keys.PUBLIC_BACKEND_URL] = baseUrl.trim().trimEnd('/')
        }
        if (bearerToken != null) {
            if (bearerToken.isBlank()) {
                credentialStore.clearSecret("public_backend_token")
            } else {
                credentialStore.putSecret("public_backend_token", bearerToken.trim())
            }
        }
    }

    fun getPublicBackendToken(): String = credentialStore.getSecret("public_backend_token")

    fun savePublicBotToken(token: String) {
        if (token.isBlank()) {
            credentialStore.clearSecret("public_bot_token")
        } else {
            credentialStore.putSecret("public_bot_token", token.trim())
        }
    }

    fun getPublicBotToken(): String = credentialStore.getSecret("public_bot_token")
}
