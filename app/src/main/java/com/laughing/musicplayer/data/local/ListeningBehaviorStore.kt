package com.laughing.musicplayer.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.laughing.musicplayer.core.model.GenreType
import com.laughing.musicplayer.core.model.Track
import com.laughing.musicplayer.core.util.VietnameseTextNormalizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Calendar

private val Context.listeningBehaviorDataStore by preferencesDataStore(name = "listening_behavior_prefs")

enum class TimeOfDayBucket(
    val labelVi: String,
    val moodPhraseVi: String,
    val fallbackDiscoveryQuery: String,
    val favoredGenres: Set<GenreType>,
    val favoredKeywords: Set<String>
) {
    MORNING(
        labelVi = "Buổi sáng",
        moodPhraseVi = "Buổi sáng tràn năng lượng",
        fallbackDiscoveryQuery = "Nhạc V-Pop Acoustic Buổi Sáng Tươi Vui",
        favoredGenres = setOf(GenreType.POP, GenreType.ACOUSTIC, GenreType.EDM),
        favoredKeywords = setOf("v-pop", "acoustic", "chill", "indie")
    ),
    AFTERNOON(
        labelVi = "Buổi chiều",
        moodPhraseVi = "Buổi chiều tập trung",
        fallbackDiscoveryQuery = "Nhạc Lofi Chill Tập Trung Làm Việc",
        favoredGenres = setOf(GenreType.ACOUSTIC, GenreType.POP, GenreType.EDM, GenreType.JAZZ),
        favoredKeywords = setOf("lofi", "chill", "remix", "edm", "piano")
    ),
    EVENING(
        labelVi = "Buổi tối",
        moodPhraseVi = "Buổi tối thư giãn",
        fallbackDiscoveryQuery = "Nhạc Ballad V-Pop Acoustic Thư Giãn",
        favoredGenres = setOf(GenreType.BALLAD, GenreType.POP, GenreType.ACOUSTIC, GenreType.TRU_TINH),
        favoredKeywords = setOf("ballad", "acoustic", "v-pop", "cover", "live")
    ),
    LATE_NIGHT(
        labelVi = "Đêm khuya",
        moodPhraseVi = "Đêm khuya sâu lắng",
        fallbackDiscoveryQuery = "Nhạc Lofi Ballad Đêm Khuya Buồn Chill",
        favoredGenres = setOf(GenreType.BALLAD, GenreType.TRU_TINH, GenreType.BOLERO, GenreType.CLASSICAL),
        favoredKeywords = setOf("lofi", "ballad", "chill", "piano", "bolero")
    );

    companion object {
        fun fromHour(hourOfDay: Int): TimeOfDayBucket {
            return when (hourOfDay) {
                in 5..11 -> MORNING
                in 12..17 -> AFTERNOON
                in 18..21 -> EVENING
                else -> LATE_NIGHT
            }
        }

        fun current(): TimeOfDayBucket {
            val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            return fromHour(hour)
        }
    }
}

@Serializable
data class TrackListeningSignal(
    val playStarts: Int = 0,
    val completions: Int = 0,
    val earlySkips: Int = 0,
    val lastUpdatedMs: Long = 0L
) {
    val completionRate: Float
        get() {
            val total = completions + earlySkips
            if (total <= 0) return if (playStarts > 0) 0.65f else 0.5f
            return completions.toFloat() / total.toFloat()
        }
}

@Serializable
data class ListeningBehaviorSnapshot(
    val trackSignals: Map<String, TrackListeningSignal> = emptyMap(),
    val artistAffinity: Map<String, Float> = emptyMap(),
    val keywordAffinity: Map<String, Float> = emptyMap(),
    val timeBucketGenres: Map<String, Map<String, Int>> = emptyMap()
)

class ListeningBehaviorStore(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val _snapshot = MutableStateFlow(ListeningBehaviorSnapshot())
    val snapshot: StateFlow<ListeningBehaviorSnapshot> = _snapshot.asStateFlow()

    @Volatile
    private var loaded = false

    init {
        scope.launch {
            getSnapshot()
        }
    }

    suspend fun getSnapshot(): ListeningBehaviorSnapshot {
        if (loaded) return _snapshot.value
        return mutex.withLock {
            if (loaded) return@withLock _snapshot.value
            val prefs = appContext.listeningBehaviorDataStore.data.first()
            val raw = prefs[KEY_SNAPSHOT_JSON]
            val decoded = if (!raw.isNullOrBlank()) {
                runCatching { json.decodeFromString<ListeningBehaviorSnapshot>(raw) }
                    .getOrDefault(ListeningBehaviorSnapshot())
            } else {
                ListeningBehaviorSnapshot()
            }
            _snapshot.value = decoded
            loaded = true
            decoded
        }
    }

    suspend fun recordTrackOutcome(
        track: Track,
        completed: Boolean,
        skippedEarly: Boolean,
        hourOfDay: Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    ) {
        if (!completed && !skippedEarly) return
        mutex.withLock {
            if (!loaded) {
                val prefs = appContext.listeningBehaviorDataStore.data.first()
                val raw = prefs[KEY_SNAPSHOT_JSON]
                if (!raw.isNullOrBlank()) {
                    _snapshot.value = runCatching {
                        json.decodeFromString<ListeningBehaviorSnapshot>(raw)
                    }.getOrDefault(ListeningBehaviorSnapshot())
                }
                loaded = true
            }
            val now = System.currentTimeMillis()
            val current = _snapshot.value

            // 1. Per-track signal
            val updatedSignals = current.trackSignals.toMutableMap()
            val prevSignal = updatedSignals[track.id] ?: TrackListeningSignal()
            updatedSignals[track.id] = TrackListeningSignal(
                playStarts = prevSignal.playStarts + 1,
                completions = prevSignal.completions + if (completed) 1 else 0,
                earlySkips = prevSignal.earlySkips + if (skippedEarly) 1 else 0,
                lastUpdatedMs = now
            )
            val prunedSignals = if (updatedSignals.size > MAX_TRACK_SIGNALS) {
                updatedSignals.entries
                    .sortedByDescending { it.value.lastUpdatedMs }
                    .take(MAX_TRACK_SIGNALS)
                    .associate { it.key to it.value }
            } else {
                updatedSignals
            }

            // 2. Artist affinity
            val updatedArtists = current.artistAffinity.toMutableMap()
            val artistKey = normalizeArtistKey(track.artist)
            if (artistKey.isNotBlank()) {
                val delta = when {
                    completed -> 2.0f
                    skippedEarly -> -1.2f
                    else -> 0.5f
                }
                val nextScore = ((updatedArtists[artistKey] ?: 0f) + delta).coerceIn(-6f, 40f)
                updatedArtists[artistKey] = nextScore
            }
            val prunedArtists = if (updatedArtists.size > MAX_ARTISTS) {
                updatedArtists.entries
                    .sortedByDescending { kotlin.math.abs(it.value) }
                    .take(MAX_ARTISTS)
                    .associate { it.key to it.value }
            } else {
                updatedArtists
            }

            // 3. Keyword affinity (remix, lofi, ballad, edm, v-pop, ...)
            val updatedKeywords = current.keywordAffinity.toMutableMap()
            val extractedKeywords = extractKeywords(track)
            val kwDelta = when {
                completed -> 1.5f
                skippedEarly -> -0.8f
                else -> 0.4f
            }
            extractedKeywords.forEach { kw ->
                updatedKeywords[kw] = ((updatedKeywords[kw] ?: 0f) + kwDelta).coerceIn(-4f, 35f)
            }

            // 4. Time-of-day genre profile (only reward completed or non-skipped plays)
            val updatedTimeMap = current.timeBucketGenres.mapValues { it.value.toMutableMap() }.toMutableMap()
            if (completed) {
                val bucket = TimeOfDayBucket.fromHour(hourOfDay).name
                val bucketCounts = updatedTimeMap.getOrPut(bucket) { mutableMapOf() }
                val gName = track.genre.name
                bucketCounts[gName] = (bucketCounts[gName] ?: 0) + 1
            }

            val nextSnapshot = ListeningBehaviorSnapshot(
                trackSignals = prunedSignals,
                artistAffinity = prunedArtists,
                keywordAffinity = updatedKeywords,
                timeBucketGenres = updatedTimeMap
            )
            _snapshot.value = nextSnapshot
            val encoded = json.encodeToString(nextSnapshot)
            appContext.listeningBehaviorDataStore.edit { prefs ->
                prefs[KEY_SNAPSHOT_JSON] = encoded
            }
        }
    }

    companion object {
        private val KEY_SNAPSHOT_JSON = stringPreferencesKey("listening_behavior_snapshot_v1")
        private const val MAX_TRACK_SIGNALS = 300
        private const val MAX_ARTISTS = 80

        private val KEYWORD_PATTERNS: List<Pair<String, Regex>> = listOf(
            "Remix" to Regex("\\b(remix|rmx|mixset|dj)\\b"),
            "Lofi" to Regex("\\b(lofi|lo-fi)\\b"),
            "Ballad" to Regex("\\b(ballad|buon|tinh ca)\\b"),
            "EDM" to Regex("\\b(edm|house|trance|techno|dubstep)\\b"),
            "V-Pop" to Regex("\\b(vpop|v-pop|nhac viet|nhac tre)\\b"),
            "Acoustic" to Regex("\\b(acoustic|moc|guitar)\\b"),
            "Bolero" to Regex("\\b(bolero|tru tinh)\\b"),
            "Chill" to Regex("\\b(chill|relax|thu gian)\\b"),
            "Live" to Regex("\\b(live|session|concert)\\b"),
            "Cover" to Regex("\\b(cover)\\b"),
            "Vinahouse" to Regex("\\b(vinahouse)\\b"),
            "Rap" to Regex("\\b(rap|hiphop|hip-hop)\\b"),
            "Indie" to Regex("\\b(indie)\\b"),
            "Piano" to Regex("\\b(piano|instrumental|khong loi)\\b")
        )

        fun normalizeArtistKey(rawArtist: String): String {
            val cleaned = rawArtist
                .replace(Regex("(?i)\\s*-\\s*topic$"), "")
                .trim()
            if (cleaned.isBlank() ||
                cleaned.equals("Unknown", ignoreCase = true) ||
                cleaned.equals("Nghệ sĩ không rõ", ignoreCase = true) ||
                cleaned.equals("YouTube Music", ignoreCase = true)
            ) {
                return ""
            }
            return cleaned.lowercase()
        }

        fun extractKeywords(track: Track): List<String> {
            val normalizedText = VietnameseTextNormalizer.normalize(
                "${track.title} ${track.album} ${track.genre.displayNameVi}"
            )
            val hits = mutableListOf<String>()
            KEYWORD_PATTERNS.forEach { (label, regex) ->
                if (regex.containsMatchIn(normalizedText)) {
                    hits += label
                }
            }
            // Add explicit Genre mapping if not already matched
            when (track.genre) {
                GenreType.REMIX -> if ("Remix" !in hits) hits += "Remix"
                GenreType.HOUSE -> if ("EDM" !in hits) hits += "EDM"
                GenreType.BALLAD, GenreType.TRU_TINH -> if ("Ballad" !in hits) hits += "Ballad"
                GenreType.EDM -> if ("EDM" !in hits) hits += "EDM"
                GenreType.ACOUSTIC -> if ("Acoustic" !in hits) hits += "Acoustic"
                GenreType.BOLERO -> if ("Bolero" !in hits) hits += "Bolero"
                GenreType.RAP_HIPHOP -> if ("Rap" !in hits) hits += "Rap"
                GenreType.CLASSICAL -> if ("Piano" !in hits) hits += "Piano"
                GenreType.POP -> if ("V-Pop" !in hits) hits += "V-Pop"
                GenreType.JAZZ -> if ("Chill" !in hits) hits += "Chill"
                else -> Unit
            }
            return hits.distinct()
        }
    }
}
