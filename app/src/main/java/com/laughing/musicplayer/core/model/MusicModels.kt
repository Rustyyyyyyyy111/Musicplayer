package com.laughing.musicplayer.core.model

import kotlinx.serialization.Serializable

enum class MusicSource(val labelVi: String) {
    TELEGRAM("Telegram"),
    YOUTUBE_MUSIC("YouTube Music"),
    OFFLINE("Offline")
}

enum class ThemeMode(val labelVi: String) {
    LIGHT("Chế độ sáng"),
    DARK("Chế độ tối"),
    LIQUID_GLASS("Liquid Glass")
}

enum class AudioFormat(val extension: String, val isLossless: Boolean) {
    MP3("mp3", false),
    FLAC("flac", true),
    WAV("wav", true),
    M4A("m4a", false),
    AAC("aac", false),
    ALAC("alac", true),
    OGG("ogg", false),
    OPUS("opus", false),
    APE("ape", true),
    OTHER("audio", false)
}

enum class GenreType(val displayNameVi: String, val defaultEqPreset: EqPreset) {
    BALLAD("Ballad", EqPreset.BALLAD),
    TRU_TINH("Trữ tình", EqPreset.BALLAD),
    BOLERO("Bolero", EqPreset.BOLERO),
    EDM("EDM", EqPreset.EDM_REMIX),
    REMIX("Remix", EqPreset.EDM_REMIX),
    HOUSE("House", EqPreset.EDM_REMIX),
    POP("Pop", EqPreset.VOCAL),
    ROCK("Rock", EqPreset.BASS_BOOST),
    RAP_HIPHOP("Rap / Hip-hop", EqPreset.BASS_BOOST),
    ACOUSTIC("Acoustic", EqPreset.ACOUSTIC),
    CLASSICAL("Classical", EqPreset.ACOUSTIC),
    JAZZ("Jazz", EqPreset.VOCAL),
    OTHER("Other", EqPreset.FLAT)
}

enum class EqPreset(val displayNameVi: String, val gainsDb: List<Float>) {
    FLAT("Flat", listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)),
    BALLAD("Ballad / Trữ tình", listOf(1.5f, 1.0f, 0.5f, 0.0f, 1.5f, 2.5f, 2.0f, 1.5f, 1.0f, 0.5f)),
    BOLERO("Bolero", listOf(2.0f, 2.5f, 1.5f, 0.5f, 2.0f, 3.0f, 2.0f, 1.0f, 0.5f, 0.0f)),
    EDM_REMIX("EDM / Remix", listOf(4.5f, 4.0f, 2.5f, 0.5f, -1.0f, 0.0f, 1.5f, 3.0f, 4.0f, 3.5f)),
    BASS_BOOST("Bass Boost", listOf(6.0f, 5.0f, 3.5f, 1.5f, 0.0f, 0.0f, 0.5f, 1.0f, 1.0f, 0.5f)),
    VOCAL("Vocal", listOf(-1.5f, -1.0f, 0.0f, 2.0f, 3.5f, 4.0f, 3.0f, 1.5f, 0.5f, 0.0f)),
    ACOUSTIC("Acoustic", listOf(2.0f, 1.5f, 1.0f, 0.5f, 1.5f, 2.0f, 2.5f, 2.0f, 1.5f, 1.0f)),
    CAR_AUDIO("Car Audio", listOf(4.0f, 3.0f, 1.5f, -0.5f, 1.0f, 2.5f, 3.0f, 2.5f, 2.0f, 1.5f)),
    CUSTOM("Custom", listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f))
}

enum class DownloadStatus(val labelVi: String) {
    NONE("Chưa tải"),
    QUEUED("Đang chờ"),
    DOWNLOADING("Đang tải"),
    PAUSED("Đã tạm dừng"),
    COMPLETED("Đã lưu"),
    FAILED("Thất bại")
}

enum class TelegramChatType(val labelVi: String) {
    PUBLIC_CHANNEL("Kênh công khai"),
    PRIVATE_CHANNEL("Kênh riêng tư"),
    PUBLIC_GROUP("Nhóm công khai"),
    PRIVATE_GROUP("Nhóm riêng tư"),
    SUPERGROUP("Siêu nhóm"),
    FORUM_GROUP("Nhóm chủ đề (Forum)"),
    PRIVATE_CHAT("Trò chuyện riêng"),
    SAVED_MESSAGES("Tin nhắn đã lưu")
}

enum class RepeatMode {
    OFF,
    ALL,
    ONE
}

enum class CrossfadeOption(val seconds: Int, val labelVi: String) {
    OFF(0, "Tắt"),
    TWO_SECONDS(2, "2 giây"),
    FOUR_SECONDS(4, "4 giây"),
    SIX_SECONDS(6, "6 giây")
}

enum class CacheLimitOption(val bytes: Long, val labelVi: String) {
    GB_1(1L * 1024 * 1024 * 1024, "1 GB"),
    GB_2(2L * 1024 * 1024 * 1024, "2 GB"),
    GB_3(3L * 1024 * 1024 * 1024, "3 GB"),
    GB_5(5L * 1024 * 1024 * 1024, "5 GB"),
    CUSTOM(4L * 1024 * 1024 * 1024, "Tùy chỉnh")
}

enum class SleepTimerMode(val labelVi: String) {
    OFF("Tắt"),
    AFTER_MINUTES("Tắt sau N phút"),
    END_OF_CURRENT_TRACK("Tắt sau bài hát hiện tại"),
    AFTER_N_TRACKS("Tắt sau N bài tiếp theo")
}

enum class SmartPlaylistType(val id: String, val titleVi: String, val descriptionVi: String) {
    NOT_PLAYED_30_DAYS(
        id = "smart_not_played_30d",
        titleVi = "Chưa nghe trong 30 ngày qua",
        descriptionVi = "Những bài hát chưa được phát lại trong vòng 30 ngày gần đây"
    ),
    MOST_PLAYED_THIS_MONTH(
        id = "smart_most_played_month",
        titleVi = "Nghe nhiều nhất trong tháng",
        descriptionVi = "Bảng xếp hạng bài hát bạn nghe nhiều nhất tháng này"
    ),
    HIGH_ENERGY_130_BPM(
        id = "smart_high_energy_130bpm",
        titleVi = "Nhạc sôi động (>130 BPM)",
        descriptionVi = "Các bản nhạc tiết tấu nhanh trên 130 nhịp mỗi phút"
    )
}

enum class UnifiedSearchTab(val labelVi: String) {
    ALL("Tất cả"),
    OFFLINE("Thư viện Offline"),
    TELEGRAM("Telegram"),
    YOUTUBE_MUSIC("YouTube Music")
}

enum class LibraryCategory(val labelVi: String) {
    ALL_SONGS("Tất cả bài hát"),
    ARTISTS("Nghệ sĩ"),
    ALBUMS("Album"),
    GENRES("Thể loại"),
    PLAYLISTS("Playlist"),
    FAVORITES("Yêu thích"),
    DOWNLOADED("Đã lưu"),
    RECENT("Gần đây")
}

enum class TelegramOrigin(val labelVi: String) {
    PERSONAL_TELEGRAM("Telegram cá nhân"),
    PUBLIC_TELEGRAM("Kênh công khai")
}

enum class PublicChannelAccessMode(val labelVi: String) {
    BACKEND_API("Companion Backend API"),
    BOT_API("Telegram Bot API")
}

enum class PublicSyncStatus(val labelVi: String) {
    IDLE("Sẵn sàng"),
    SYNCING("Đang đồng bộ"),
    PAUSED("Đã tạm dừng"),
    ERROR("Lỗi")
}

data class PublicChannelItem(
    val channelKey: String,
    val channelId: Long? = null,
    val username: String,
    val displayTitle: String,
    val avatarUrl: String? = null,
    val discoveredCount: Int = 0,
    val lastSyncedEpochMs: Long = 0L,
    val syncStatus: PublicSyncStatus = PublicSyncStatus.IDLE,
    val isPaused: Boolean = false,
    val lastCursor: String? = null,
    val lastError: String? = null
)

@Serializable
data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val source: MusicSource,
    val durationMs: Long,
    val format: AudioFormat,
    val bitrateKbps: Int = 0,
    val sampleRateHz: Int = 44100,
    val bitDepth: Int? = null,
    val fileSizeBytes: Long = 0L,
    val fileHash: String? = null,
    val fileName: String = "",
    val artworkUri: String? = null,
    val streamUri: String? = null,
    val localPath: String? = null,
    val telegramChatId: Long? = null,
    val telegramChatTitle: String? = null,
    val telegramMessageId: Long? = null,
    val telegramFileId: Int? = null,
    val telegramRemoteFileId: String? = null,
    val telegramRemoteUniqueId: String? = null,
    val mimeType: String? = null,
    val lastVerifiedAtEpochMs: Long = 0L,
    val telegramOrigin: TelegramOrigin = TelegramOrigin.PERSONAL_TELEGRAM,
    val publicChannelUsername: String? = null,
    val telegramThreadId: Long? = null,
    val telegramTopicName: String? = null,
    val youtubeVideoId: String? = null,
    val genre: GenreType = GenreType.OTHER,
    val genreManuallyEdited: Boolean = false,
    val isVoiceMessage: Boolean = false,
    val downloadStatus: DownloadStatus = DownloadStatus.NONE,
    val downloadProgress: Float = 0f,
    val isFavorite: Boolean = false,
    val dateAddedEpochMs: Long = System.currentTimeMillis(),
    val lastPlayedEpochMs: Long = 0L,
    val playCount: Int = 0,
    val loudnessGainDb: Float = 0f,
    val bpm: Int = 0
)

data class ArtistGroup(
    val key: String,
    val name: String,
    val trackCount: Int,
    val artworkUri: String? = null,
    val isTelegramTopic: Boolean = false,
    val chatId: Long? = null,
    val threadId: Long? = null,
    val chatTitle: String? = null
)

data class AlbumGroup(
    val name: String,
    val artist: String,
    val trackCount: Int,
    val artworkUri: String? = null
)

data class DuplicateGroup(
    val groupId: String,
    val reason: String,
    val tracks: List<Track>,
    val recommendedKeepId: String
)

data class TelegramChatItem(
    val chatId: Long,
    val title: String,
    val chatType: TelegramChatType,
    val isForum: Boolean,
    val isSelected: Boolean,
    val audioCount: Int,
    val topicCount: Int,
    val lastSyncedMessageId: Long,
    val lastSyncedEpochMs: Long
)

data class TelegramTopicItem(
    val chatId: Long,
    val messageThreadId: Long,
    val topicId: Long,
    val name: String,
    val iconUrl: String?,
    val trackCount: Int
)

data class PlaylistItem(
    val id: String,
    val name: String,
    val description: String,
    val trackCount: Int,
    val isSmartMix: Boolean = false,
    val artworkUri: String? = null
)

data class DownloadItem(
    val id: String,
    val trackId: String,
    val title: String,
    val artist: String,
    val source: MusicSource,
    val status: DownloadStatus,
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val destinationPath: String,
    val errorMessage: String? = null,
    val updatedAtEpochMs: Long = System.currentTimeMillis()
)

data class LyricLine(
    val timestampMs: Long,
    val text: String,
    val translation: String? = null,
    val romanization: String? = null
)

data class EqState(
    val enabled: Boolean = true,
    val preset: EqPreset = EqPreset.FLAT,
    val bandGainsDb: List<Float> = EqPreset.FLAT.gainsDb,
    val preampDb: Float = 0f,
    val clippingProtection: Boolean = true,
    val autoEqByGenre: Boolean = true,
    val loudnessNormalization: Boolean = true
)

enum class ProxyType {
    NONE,
    HTTP,
    SOCKS5
}

data class ProxyConfig(
    val type: ProxyType = ProxyType.NONE,
    val host: String = "",
    val port: Int = 1080,
    val username: String = "",
    val password: String = ""
)

data class ActivityLogItem(
    val id: Long = 0L,
    val timestampMs: Long = System.currentTimeMillis(),
    val category: String,
    val level: String,
    val message: String
)

data class AudioQualitySpecDisplay(
    val formatBadge: String,
    val specText: String,
    val isLossless: Boolean
)

fun Track.toAudioQualityDisplay(): AudioQualitySpecDisplay {
    val isLossless = format.isLossless
    val badge = when {
        format == AudioFormat.FLAC -> "FLAC"
        format == AudioFormat.ALAC -> "ALAC"
        format == AudioFormat.WAV -> "WAV"
        format == AudioFormat.APE -> "APE"
        format == AudioFormat.OPUS -> "OPUS"
        format == AudioFormat.M4A -> "M4A"
        format == AudioFormat.MP3 -> "MP3"
        format == AudioFormat.AAC -> "AAC"
        format == AudioFormat.OGG -> "OGG"
        else -> format.extension.uppercase()
    }
    val srKhz = if (sampleRateHz > 0) {
        if (sampleRateHz % 1000 == 0) "${sampleRateHz / 1000}kHz"
        else String.format(java.util.Locale.US, "%.1fkHz", sampleRateHz / 1000f)
    } else null
    val depthPart = if (isLossless) {
        val depth = bitDepth?.takeIf { it > 0 } ?: 16
        if (srKhz != null) "$depth-bit / $srKhz" else "$depth-bit"
    } else {
        srKhz
    }
    val bitratePart = bitrateKbps.takeIf { it > 0 }?.let { "$it kbps" }
    val spec = listOfNotNull(depthPart, bitratePart).joinToString(" • ")
    return AudioQualitySpecDisplay(
        formatBadge = badge,
        specText = spec,
        isLossless = isLossless
    )
}

