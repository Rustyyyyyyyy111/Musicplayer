package com.example.integrations

import com.example.core.model.GenreType
import com.example.core.model.PlaylistItem
import com.example.core.model.Track
import com.example.core.util.ActivityLogger
import com.example.data.local.PlaylistDao
import com.example.data.local.PlaylistEntity
import com.example.data.local.PlaylistTrackCrossRef
import com.example.data.local.TrackDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

class PlaylistAndSmartMixManager(
    private val playlistDao: PlaylistDao,
    private val trackDao: TrackDao,
    private val logger: ActivityLogger
) {
    val playlists: Flow<List<PlaylistItem>> = playlistDao.observePlaylists().map { list ->
        list.map { entity ->
            val count = playlistDao.countTracksInPlaylist(entity.id)
            entity.toDomain(count)
        }
    }

    fun observeTracksInPlaylist(playlistId: String): Flow<List<Track>> {
        return playlistDao.observeTracksForPlaylist(playlistId).map { list ->
            list.map { it.toDomain() }
        }
    }

    suspend fun createPlaylist(name: String, description: String = ""): String {
        val id = "pl_${UUID.randomUUID()}"
        playlistDao.upsertPlaylist(
            PlaylistEntity(
                id = id,
                name = name.trim(),
                description = description.trim(),
                isSmartMix = false,
                artworkUri = null,
                createdAtEpochMs = System.currentTimeMillis()
            )
        )
        logger.log("PLAYLIST", "INFO", "Đã tạo playlist '$name'")
        return id
    }

    suspend fun addTrackToPlaylist(playlistId: String, trackId: String) {
        val count = playlistDao.countTracksInPlaylist(playlistId)
        playlistDao.addTrackRefs(listOf(PlaylistTrackCrossRef(playlistId, trackId, count)))
    }

    suspend fun deletePlaylist(playlistId: String) {
        playlistDao.deletePlaylist(playlistId)
    }

    /**
     * AI Smart Mix: Builds a cohesive mix based on seed track / genre affinity / listening history,
     * ensuring zero duplicate tracks in continuous playback.
     */
    suspend fun generateSmartMix(seedTrack: Track? = null, maxTracks: Int = 25): Pair<String, List<Track>> {
        val allTracks = trackDao.getAllTracksOnce().map { it.toDomain() }
        if (allTracks.isEmpty()) return "Smart Mix" to emptyList()

        val targetGenre = seedTrack?.genre
            ?: allTracks.maxByOrNull { it.playCount }?.genre
            ?: GenreType.POP

        val scored = allTracks
            .distinctBy { "${it.title.lowercase()}|${it.artist.lowercase()}" }
            .sortedByDescending { candidate ->
                var score = 0
                if (candidate.id == seedTrack?.id) score += 500
                if (candidate.genre == targetGenre) score += 120
                if (seedTrack != null && candidate.artist.equals(seedTrack.artist, ignoreCase = true)) score += 90
                if (candidate.isFavorite) score += 60
                score += minOf(candidate.playCount * 5, 50)
                score
            }
            .take(maxTracks)

        val mixName = if (seedTrack != null) {
            "Smart Mix: ${seedTrack.title}"
        } else {
            "Smart Mix • ${targetGenre.displayNameVi}"
        }
        val mixId = "smart_${targetGenre.name}_${System.currentTimeMillis()}"
        playlistDao.upsertPlaylist(
            PlaylistEntity(
                id = mixId,
                name = mixName,
                description = "Phối nhạc thông minh dựa trên gu nghe & thể loại ${targetGenre.displayNameVi}",
                isSmartMix = true,
                artworkUri = scored.firstOrNull()?.artworkUri,
                createdAtEpochMs = System.currentTimeMillis()
            )
        )
        playlistDao.addTrackRefs(
            scored.mapIndexed { idx, track ->
                PlaylistTrackCrossRef(mixId, track.id, idx)
            }
        )
        logger.log("SMART_MIX", "INFO", "Đã tạo $mixName (${scored.size} bài)")
        return mixId to scored
    }

    /**
     * Smart Auto-Queue: picks next tracks avoiding recent duplicates.
     */
    suspend fun suggestAutoQueue(currentTrack: Track, alreadyQueuedIds: Set<String>, limit: Int = 10): List<Track> {
        val all = trackDao.getAllTracksOnce().map { it.toDomain() }
        val seenSignatures = mutableSetOf("${currentTrack.title.lowercase()}|${currentTrack.artist.lowercase()}")
        return all.filter { candidate ->
            if (candidate.id in alreadyQueuedIds || candidate.id == currentTrack.id) return@filter false
            val sig = "${candidate.title.lowercase()}|${candidate.artist.lowercase()}"
            seenSignatures.add(sig)
        }.sortedByDescending { candidate ->
            var score = 0
            if (candidate.genre == currentTrack.genre) score += 100
            if (candidate.artist.equals(currentTrack.artist, ignoreCase = true)) score += 70
            if (candidate.isFavorite) score += 30
            score
        }.take(limit)
    }

    fun exportToM3u(playlistName: String, tracks: List<Track>): String {
        return buildString {
            appendLine("#EXTM3U")
            appendLine("#PLAYLIST:$playlistName")
            tracks.forEach { t ->
                val sec = (t.durationMs / 1000L).coerceAtLeast(-1L)
                appendLine("#EXTINF:$sec,${t.artist} - ${t.title}")
                appendLine(t.localPath ?: t.streamUri ?: t.fileName)
            }
        }
    }

    suspend fun importFromM3u(playlistName: String, m3uContent: String): Int {
        val allTracks = trackDao.getAllTracksOnce().map { it.toDomain() }
        val lines = m3uContent.lines().map { it.trim() }.filter { it.isNotBlank() && !it.startsWith("#") }
        val matched = mutableListOf<Track>()
        for (entry in lines) {
            val fileNameOnly = entry.substringAfterLast('/').substringAfterLast('\\')
            val hit = allTracks.firstOrNull {
                it.localPath == entry || it.streamUri == entry || it.fileName.equals(fileNameOnly, ignoreCase = true)
            }
            if (hit != null && matched.none { it.id == hit.id }) {
                matched += hit
            }
        }
        val id = createPlaylist(playlistName, "Nhập từ M3U (${matched.size} bài)")
        playlistDao.addTrackRefs(matched.mapIndexed { i, t -> PlaylistTrackCrossRef(id, t.id, i) })
        logger.log("M3U", "INFO", "Đã nhập M3U '$playlistName': khớp ${matched.size}/${lines.size} bài")
        return matched.size
    }
}
