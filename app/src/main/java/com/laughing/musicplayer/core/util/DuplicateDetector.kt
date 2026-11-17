package com.laughing.musicplayer.core.util

import com.laughing.musicplayer.core.model.AudioFormat
import com.laughing.musicplayer.core.model.DownloadStatus
import com.laughing.musicplayer.core.model.DuplicateGroup
import com.laughing.musicplayer.core.model.MusicSource
import com.laughing.musicplayer.core.model.Track
import java.io.File
import java.security.MessageDigest
import kotlin.math.abs

object DuplicateDetector {

    private val versionMarkers = listOf(
        "remix", "live", "acoustic", "instrumental", "karaoke", "demo",
        "cover", "vip", "edit", "mashup", "sped up", "slowed", "reverb", "radio"
    )

    fun computeSha256(file: File): String? {
        if (!file.exists() || !file.canRead()) return null
        return runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(16 * 1024)
            file.inputStream().use { input ->
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }.getOrNull()
    }

    fun qualityScore(track: Track): Int {
        val formatScore = when (track.format) {
            AudioFormat.FLAC -> 1000
            AudioFormat.ALAC -> 980
            AudioFormat.WAV -> 950
            AudioFormat.APE -> 920
            AudioFormat.M4A -> 400
            AudioFormat.AAC -> 380
            AudioFormat.OPUS -> 360
            AudioFormat.OGG -> 340
            AudioFormat.MP3 -> 300
            AudioFormat.OTHER -> 100
        }
        val bitrateBonus = track.bitrateKbps.coerceIn(0, 2000) / 2
        val sampleRateBonus = if (track.sampleRateHz > 48000) 80 else 0
        val bitDepthBonus = if ((track.bitDepth ?: 16) >= 24) 100 else 0
        val offlineBonus = if (track.downloadStatus == DownloadStatus.COMPLETED || track.source == MusicSource.OFFLINE) 20 else 0
        return formatScore + bitrateBonus + sampleRateBonus + bitDepthBonus + offlineBonus
    }

    fun findDuplicates(tracks: List<Track>): List<DuplicateGroup> {
        if (tracks.size < 2) return emptyList()
        val groups = mutableListOf<DuplicateGroup>()
        val assigned = mutableSetOf<String>()

        // 1. Exact file hash duplicates
        tracks.filter { !it.fileHash.isNullOrBlank() }
            .groupBy { it.fileHash!! }
            .forEach { (hash, matches) ->
                if (matches.size > 1) {
                    val best = matches.maxByOrNull { qualityScore(it) } ?: matches.first()
                    groups += DuplicateGroup(
                        groupId = "hash_$hash",
                        reason = "Trùng mã băm SHA-256 tệp tin",
                        tracks = matches.sortedByDescending { qualityScore(it) },
                        recommendedKeepId = best.id
                    )
                    matches.forEach { assigned += it.id }
                }
            }

        // 2. Duration + Title + Version-marker-safe matching
        val remaining = tracks.filter { it.id !in assigned }
        val byNormalizedTitle = remaining.groupBy { extractVersionSafeSignature(it) }
        byNormalizedTitle.forEach { (sig, candidates) ->
            if (candidates.size < 2 || sig.isBlank()) return@forEach
            val clusters = mutableListOf<MutableList<Track>>()
            for (candidate in candidates) {
                val matchingCluster = clusters.firstOrNull { cluster ->
                    val ref = cluster.first()
                    val durationMatch = abs(ref.durationMs - candidate.durationMs) <= 2000L
                    val sameArtist = VietnameseTextNormalizer.normalize(ref.artist) ==
                        VietnameseTextNormalizer.normalize(candidate.artist)
                    val sameSize = ref.fileSizeBytes > 0 && candidate.fileSizeBytes > 0 &&
                        abs(ref.fileSizeBytes - candidate.fileSizeBytes) <= 4096L
                    (durationMatch && sameArtist) || sameSize
                }
                if (matchingCluster != null) {
                    matchingCluster += candidate
                } else {
                    clusters += mutableListOf(candidate)
                }
            }
            clusters.filter { it.size > 1 }.forEachIndexed { idx, cluster ->
                val best = cluster.maxByOrNull { qualityScore(it) } ?: cluster.first()
                groups += DuplicateGroup(
                    groupId = "meta_${sig}_$idx",
                    reason = "Cùng bài hát, nghệ sĩ và thời lượng (${cluster.size} bản)",
                    tracks = cluster.sortedByDescending { qualityScore(it) },
                    recommendedKeepId = best.id
                )
            }
        }
        return groups
    }

    private fun extractVersionSafeSignature(track: Track): String {
        val normTitle = VietnameseTextNormalizer.normalize(track.title)
        val markers = versionMarkers.filter { normTitle.contains(it) }.sorted().joinToString(",")
        return "$normTitle|$markers"
    }
}
