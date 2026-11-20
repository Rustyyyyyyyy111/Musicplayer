package com.laughing.musicplayer.integrations

import com.laughing.musicplayer.core.model.GenreType
import com.laughing.musicplayer.core.model.MusicSource
import com.laughing.musicplayer.core.model.Track
import com.laughing.musicplayer.core.util.ActivityLogger
import com.laughing.musicplayer.data.local.ListeningBehaviorSnapshot
import com.laughing.musicplayer.data.local.ListeningBehaviorStore
import com.laughing.musicplayer.data.local.TimeOfDayBucket
import com.laughing.musicplayer.data.youtube.YouTubeMusicRepository
import java.util.Calendar
import kotlin.math.abs

data class AiSmartMixResult(
    val mixTitle: String,
    val mixSubtitle: String,
    val timeBucket: TimeOfDayBucket,
    val topTags: List<String>,
    val tracks: List<Track>,
    val discoveryTrackIds: Set<String>,
    val familiarCount: Int,
    val discoveryCount: Int,
    val generatedAtMs: Long = System.currentTimeMillis()
)

class AiSmartMixEngine(
    private val youTubeRepository: YouTubeMusicRepository,
    private val logger: ActivityLogger
) {
    suspend fun generatePersonalMix(
        allTracks: List<Track>,
        snapshot: ListeningBehaviorSnapshot,
        hourOfDay: Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY),
        refreshSeed: Int = 0,
        allowOnlineDiscovery: Boolean = true,
        targetSize: Int = DEFAULT_MIX_SIZE
    ): AiSmartMixResult {
        val timeBucket = TimeOfDayBucket.fromHour(hourOfDay)
        val libraryTracks = allTracks.distinctBy { normalizedSignature(it) }

        val favoriteArtists = computeTopArtists(libraryTracks, snapshot)
        val topKeywords = computeTopKeywords(libraryTracks, snapshot, timeBucket)
        val preferredGenresForHour = computeTimePreferredGenres(snapshot, timeBucket)

        // Reserve ~30% slots for fresh discoveries; ~70% for familiar tracks/vibe
        val desiredTotal = if (libraryTracks.size >= targetSize || allowOnlineDiscovery) {
            targetSize
        } else {
            libraryTracks.size.coerceAtLeast(0)
        }
        val targetDiscoveryCount = ((desiredTotal * 0.30f).toInt()).coerceIn(
            minimumValue = if (desiredTotal >= 4) 1 else 0,
            maximumValue = 6
        )
        val targetFamiliarCount = (desiredTotal - targetDiscoveryCount).coerceAtLeast(0)

        // Score every local/library track
        val scoredFamiliarCandidates = libraryTracks
            .map { track ->
                val isFamiliar = track.playCount > 0 ||
                    track.isFavorite ||
                    track.lastPlayedEpochMs > 0L ||
                    (snapshot.trackSignals[track.id]?.completions ?: 0) > 0
                val score = scoreFamiliarTrack(
                    track = track,
                    snapshot = snapshot,
                    favoriteArtists = favoriteArtists,
                    topKeywords = topKeywords,
                    preferredGenresForHour = preferredGenresForHour,
                    timeBucket = timeBucket,
                    refreshSeed = refreshSeed
                )
                Triple(track, isFamiliar, score)
            }
            .sortedByDescending { it.third }

        // Select ~70% familiar tracks (or highest affinity local tracks if history is brand new)
        val familiarSelected = mutableListOf<Track>()
        val selectedSignatures = mutableSetOf<String>()
        val selectedIds = mutableSetOf<String>()
        val artistCounts = mutableMapOf<String, Int>()

        fun tryAddTrack(track: Track, targetList: MutableList<Track>, maxPerArtist: Int): Boolean {
            val sig = normalizedSignature(track)
            if (!selectedIds.add(track.id)) return false
            if (!selectedSignatures.add(sig)) {
                selectedIds.remove(track.id)
                return false
            }
            val artistKey = ListeningBehaviorStore.normalizeArtistKey(track.artist)
            if (artistKey.isNotBlank() && (artistCounts[artistKey] ?: 0) >= maxPerArtist) {
                selectedIds.remove(track.id)
                selectedSignatures.remove(sig)
                return false
            }
            if (artistKey.isNotBlank()) {
                artistCounts[artistKey] = (artistCounts[artistKey] ?: 0) + 1
            }
            targetList += track
            return true
        }

        // First pass: explicitly familiar, non-skipped tracks with artist diversity (max 3 per artist)
        fun isHeavilySkipped(track: Track): Boolean {
            val sig = snapshot.trackSignals[track.id] ?: return false
            return sig.earlySkips > sig.completions + 1
        }

        scoredFamiliarCandidates
            .filter { it.second && !isHeavilySkipped(it.first) }
            .forEach { (track, _, _) ->
                if (familiarSelected.size < targetFamiliarCount) {
                    tryAddTrack(track, familiarSelected, maxPerArtist = 3)
                }
            }

        // Second pass: relax per-artist cap for remaining familiar non-skipped tracks if library has few artists
        if (familiarSelected.size < targetFamiliarCount) {
            scoredFamiliarCandidates
                .filter { it.second && !isHeavilySkipped(it.first) }
                .forEach { (track, _, _) ->
                    if (familiarSelected.size < targetFamiliarCount) {
                        tryAddTrack(track, familiarSelected, maxPerArtist = Int.MAX_VALUE)
                    }
                }
        }

        // Third pass if brand new user has fewer played tracks: fill familiar slots with highest vibe matches
        if (familiarSelected.size < targetFamiliarCount) {
            scoredFamiliarCandidates
                .filter { !isHeavilySkipped(it.first) }
                .forEach { (track, _, _) ->
                    if (familiarSelected.size < targetFamiliarCount) {
                        tryAddTrack(track, familiarSelected, maxPerArtist = Int.MAX_VALUE)
                    }
                }
        }

        // Select ~30% Fresh Discovery tracks (local unplayed/rare + optional YouTube discovery fallback)
        val discoverySelected = mutableListOf<Track>()
        val localFreshCandidates = libraryTracks
            .filter { candidate ->
                candidate.id !in selectedIds &&
                    (candidate.playCount == 0 || candidate.lastPlayedEpochMs == 0L) &&
                    !isHeavilySkipped(candidate) &&
                    (snapshot.trackSignals[candidate.id]?.earlySkips ?: 0) == 0
            }
            .sortedByDescending { candidate ->
                scoreDiscoveryTrack(
                    track = candidate,
                    favoriteArtists = favoriteArtists,
                    topKeywords = topKeywords,
                    preferredGenresForHour = preferredGenresForHour,
                    timeBucket = timeBucket,
                    refreshSeed = refreshSeed
                )
            }

        val ytBudget = if (allowOnlineDiscovery) {
            minOf(
                targetDiscoveryCount,
                if (localFreshCandidates.size < targetDiscoveryCount) targetDiscoveryCount else 3
            )
        } else {
            0
        }
        val localFreshBudget = (targetDiscoveryCount - ytBudget).coerceAtLeast(0)

        localFreshCandidates.forEach { candidate ->
            if (discoverySelected.size < localFreshBudget) {
                tryAddTrack(candidate, discoverySelected, maxPerArtist = 2)
            }
        }

        if (ytBudget > 0 && discoverySelected.size < targetDiscoveryCount) {
            val discoveryQueries = buildYouTubeDiscoveryQueries(
                favoriteArtists = favoriteArtists,
                topKeywords = topKeywords,
                timeBucket = timeBucket,
                refreshSeed = refreshSeed
            )
            val ytResults = youTubeRepository.searchDiscoveryTracks(
                queries = discoveryQueries,
                excludeIds = selectedIds,
                maxTracks = (targetDiscoveryCount - discoverySelected.size).coerceAtLeast(1)
            )
            ytResults.forEach { ytTrack ->
                if (discoverySelected.size < targetDiscoveryCount) {
                    tryAddTrack(ytTrack, discoverySelected, maxPerArtist = 2)
                }
            }
        }

        // If online discovery returned fewer items (e.g. offline mode), backfill remaining discovery slots locally
        if (discoverySelected.size < targetDiscoveryCount) {
            localFreshCandidates.forEach { candidate ->
                if (discoverySelected.size < targetDiscoveryCount) {
                    tryAddTrack(candidate, discoverySelected, maxPerArtist = Int.MAX_VALUE)
                }
            }
        }

        // Final backfill from non-skipped remaining tracks, then last-resort fallback if library is tiny
        if (familiarSelected.size + discoverySelected.size < targetSize) {
            scoredFamiliarCandidates
                .filter { !isHeavilySkipped(it.first) }
                .forEach { (track, _, _) ->
                    if (familiarSelected.size + discoverySelected.size < targetSize) {
                        if (track.playCount == 0 && discoverySelected.size < targetDiscoveryCount + 2) {
                            tryAddTrack(track, discoverySelected, maxPerArtist = Int.MAX_VALUE)
                        } else {
                            tryAddTrack(track, familiarSelected, maxPerArtist = Int.MAX_VALUE)
                        }
                    }
                }
        }
        if (familiarSelected.size + discoverySelected.size < desiredTotal) {
            scoredFamiliarCandidates.forEach { (track, _, _) ->
                if (familiarSelected.size + discoverySelected.size < desiredTotal) {
                    tryAddTrack(track, familiarSelected, maxPerArtist = Int.MAX_VALUE)
                }
            }
        }

        val interleaved = interleaveFamiliarAndDiscovery(familiarSelected, discoverySelected)
        val discoveryIds = discoverySelected.map { it.id }.toSet()
        val displayTags = buildDisplayTags(topKeywords, timeBucket)
        val subtitle = buildSubtitle(
            timeBucket = timeBucket,
            topTags = displayTags,
            familiarCount = familiarSelected.size,
            discoveryCount = discoverySelected.size
        )

        logger.log(
            "AI_SMART_MIX",
            "INFO",
            "Đã tạo Mix Cá Nhân AI (${timeBucket.labelVi}): ${familiarSelected.size} quen thuộc + ${discoverySelected.size} khám phá mới"
        )

        return AiSmartMixResult(
            mixTitle = "Mix Cá Nhân AI",
            mixSubtitle = subtitle,
            timeBucket = timeBucket,
            topTags = displayTags,
            tracks = interleaved,
            discoveryTrackIds = discoveryIds,
            familiarCount = familiarSelected.size,
            discoveryCount = discoverySelected.size
        )
    }

    internal fun scoreFamiliarTrack(
        track: Track,
        snapshot: ListeningBehaviorSnapshot,
        favoriteArtists: Set<String>,
        topKeywords: List<String>,
        preferredGenresForHour: Set<GenreType>,
        timeBucket: TimeOfDayBucket,
        refreshSeed: Int
    ): Float {
        var score = 0f
        val signal = snapshot.trackSignals[track.id]
        val completions = signal?.completions ?: 0
        val earlySkips = signal?.earlySkips ?: 0

        score += minOf(track.playCount * 7f, 56f)
        score += completions * 18f
        score -= earlySkips * 24f
        if (signal != null && (completions + earlySkips) > 0) {
            score += (signal.completionRate - 0.5f) * 30f
        }
        if (track.isFavorite) score += 45f

        val ageDays = if (track.lastPlayedEpochMs > 0L) {
            ((System.currentTimeMillis() - track.lastPlayedEpochMs) / DAY_MS).coerceAtLeast(0L)
        } else {
            999L
        }
        if (ageDays <= 7L) {
            score += (18f - ageDays * 2f).coerceAtLeast(4f)
        }

        val artistKey = ListeningBehaviorStore.normalizeArtistKey(track.artist)
        if (artistKey.isNotBlank()) {
            score += (snapshot.artistAffinity[artistKey] ?: 0f) * 3.5f
            if (artistKey in favoriteArtists) score += 26f
        }

        val trackKeywords = ListeningBehaviorStore.extractKeywords(track)
        trackKeywords.forEach { kw ->
            score += (snapshot.keywordAffinity[kw] ?: 0f) * 2.5f
            if (kw in topKeywords) score += 16f
            if (kw.lowercase() in timeBucket.favoredKeywords) score += 10f
        }

        if (track.genre in preferredGenresForHour) score += 22f
        if (track.genre in timeBucket.favoredGenres) score += 12f
        if (track.source == MusicSource.OFFLINE) score += 6f // Instant local playback reliability

        if (refreshSeed > 0) {
            val jitter = (abs((track.id.hashCode() * 31) xor (refreshSeed * 997)) % 23)
            score += jitter.toFloat()
        }
        return score
    }

    internal fun scoreDiscoveryTrack(
        track: Track,
        favoriteArtists: Set<String>,
        topKeywords: List<String>,
        preferredGenresForHour: Set<GenreType>,
        timeBucket: TimeOfDayBucket,
        refreshSeed: Int
    ): Float {
        var score = 20f
        val artistKey = ListeningBehaviorStore.normalizeArtistKey(track.artist)
        if (artistKey in favoriteArtists) score += 28f
        val trackKeywords = ListeningBehaviorStore.extractKeywords(track)
        trackKeywords.forEach { kw ->
            if (kw in topKeywords) score += 18f
            if (kw.lowercase() in timeBucket.favoredKeywords) score += 12f
        }
        if (track.genre in preferredGenresForHour) score += 20f
        if (track.genre in timeBucket.favoredGenres) score += 14f
        if (refreshSeed > 0) {
            score += (abs((track.id.hashCode() * 17) xor (refreshSeed * 7919)) % 25).toFloat()
        }
        return score
    }

    internal fun buildYouTubeDiscoveryQueries(
        favoriteArtists: Set<String>,
        topKeywords: List<String>,
        timeBucket: TimeOfDayBucket,
        refreshSeed: Int
    ): List<String> {
        val queries = mutableListOf<String>()
        val artistList = favoriteArtists.toList()
        val pickedArtist = if (artistList.isNotEmpty()) {
            artistList[refreshSeed % artistList.size]
        } else {
            null
        }
        val pickedKeyword = if (topKeywords.isNotEmpty()) {
            topKeywords[(refreshSeed / 2) % topKeywords.size]
        } else {
            null
        }

        if (!pickedArtist.isNullOrBlank() && !pickedKeyword.isNullOrBlank()) {
            queries += "$pickedArtist $pickedKeyword nhạc mới"
        } else if (!pickedArtist.isNullOrBlank()) {
            queries += "$pickedArtist playlist nhạc hay"
        } else if (!pickedKeyword.isNullOrBlank()) {
            queries += "Nhạc $pickedKeyword hay nhất"
        }

        queries += timeBucket.fallbackDiscoveryQuery
        return queries.distinct()
    }

    private fun computeTopArtists(
        tracks: List<Track>,
        snapshot: ListeningBehaviorSnapshot
    ): Set<String> {
        val combined = mutableMapOf<String, Float>()
        snapshot.artistAffinity.forEach { (k, v) ->
            if (v > 0f) combined[k] = v
        }
        tracks.forEach { track ->
            val key = ListeningBehaviorStore.normalizeArtistKey(track.artist)
            if (key.isNotBlank()) {
                val boost = (track.playCount * 1.2f) + (if (track.isFavorite) 4f else 0f)
                if (boost > 0f) {
                    combined[key] = (combined[key] ?: 0f) + boost
                }
            }
        }
        return combined.entries
            .sortedByDescending { it.value }
            .take(5)
            .map { it.key }
            .toSet()
    }

    private fun computeTopKeywords(
        tracks: List<Track>,
        snapshot: ListeningBehaviorSnapshot,
        timeBucket: TimeOfDayBucket
    ): List<String> {
        val scores = mutableMapOf<String, Float>()
        snapshot.keywordAffinity.forEach { (k, v) ->
            if (v > 0f) scores[k] = v
        }
        tracks.filter { it.playCount > 0 || it.isFavorite }.forEach { track ->
            val weight = 1f + (track.playCount * 0.6f) + (if (track.isFavorite) 2f else 0f)
            ListeningBehaviorStore.extractKeywords(track).forEach { kw ->
                scores[kw] = (scores[kw] ?: 0f) + weight
            }
        }
        val ranked = scores.entries.sortedByDescending { it.value }.map { it.key }.take(3)
        return ranked.ifEmpty {
            timeBucket.favoredKeywords.take(3).map { kw ->
                kw.replaceFirstChar { it.uppercase() }
            }
        }
    }

    private fun computeTimePreferredGenres(
        snapshot: ListeningBehaviorSnapshot,
        timeBucket: TimeOfDayBucket
    ): Set<GenreType> {
        val counts = snapshot.timeBucketGenres[timeBucket.name].orEmpty()
        val learned = counts.entries
            .sortedByDescending { it.value }
            .take(2)
            .mapNotNull { runCatching { GenreType.valueOf(it.key) }.getOrNull() }
            .toSet()
        return learned.ifEmpty { timeBucket.favoredGenres }
    }

    private fun interleaveFamiliarAndDiscovery(
        familiar: List<Track>,
        discovery: List<Track>
    ): List<Track> {
        if (discovery.isEmpty()) return familiar
        if (familiar.isEmpty()) return discovery
        val result = mutableListOf<Track>()
        var fIdx = 0
        var dIdx = 0
        while (fIdx < familiar.size || dIdx < discovery.size) {
            repeat(2) {
                if (fIdx < familiar.size) {
                    result += familiar[fIdx++]
                }
            }
            if (dIdx < discovery.size) {
                result += discovery[dIdx++]
            }
        }
        return result
    }

    private fun buildDisplayTags(
        topKeywords: List<String>,
        timeBucket: TimeOfDayBucket
    ): List<String> {
        return (listOf(timeBucket.labelVi) + topKeywords).distinct().take(4)
    }

    private fun buildSubtitle(
        timeBucket: TimeOfDayBucket,
        topTags: List<String>,
        familiarCount: Int,
        discoveryCount: Int
    ): String {
        val vibes = topTags.filter { it != timeBucket.labelVi }.take(2).joinToString(", ")
        return if (vibes.isNotBlank()) {
            "${timeBucket.moodPhraseVi} • Gu $vibes ($familiarCount quen thuộc, $discoveryCount khám phá)"
        } else {
            "${timeBucket.moodPhraseVi} • $familiarCount bài quen thuộc & $discoveryCount gợi ý mới"
        }
    }

    companion object {
        const val DEFAULT_MIX_SIZE = 18
        private const val DAY_MS = 24L * 60L * 60L * 1000L

        internal fun normalizedSignature(track: Track): String {
            return "${track.title.trim().lowercase()}|${track.artist.trim().lowercase()}"
        }
    }
}
