package com.example.lyrics

import com.example.core.model.LyricLine
import com.example.core.model.Track
import com.example.core.util.ActivityLogger
import com.example.data.storage.UnifiedStorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

data class LyricsResult(
    val lines: List<LyricLine>,
    val isSynced: Boolean,
    val sourceLabel: String,
    val rawLrc: String
)

class LyricsEngine(
    private val storageManager: UnifiedStorageManager,
    private val httpClientProvider: () -> OkHttpClient,
    private val logger: ActivityLogger
) {
    private val timeTagRegex = Regex("\\[(\\d{1,2}):(\\d{2})(?:[.:](\\d{1,3}))?\\]")

    suspend fun loadLyricsForTrack(track: Track): LyricsResult? = withContext(Dispatchers.IO) {
        // 1. Offline cached .lrc in /sdcard/Music Player/Lyrics/
        val offlineLrc = storageManager.readLyricsFile(track.id)
        if (!offlineLrc.isNullOrBlank()) {
            val parsed = parseLrc(offlineLrc)
            if (parsed.isNotEmpty()) {
                return@withContext LyricsResult(
                    lines = parsed,
                    isSynced = true,
                    sourceLabel = "Offline (.lrc)",
                    rawLrc = offlineLrc
                )
            }
        }

        // 2. LRCLIB online lookup
        val fetched = fetchFromLrcLib(track.title, track.artist, track.album, (track.durationMs / 1000L).toInt())
        if (fetched != null) {
            storageManager.saveLyricsFile(track.id, fetched.rawLrc)
        }
        fetched
    }

    suspend fun searchLyricsManual(query: String): List<LyricsResult> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        runCatching {
            val encoded = URLEncoder.encode(query.trim(), "UTF-8")
            val request = Request.Builder()
                .url("https://lrclib.net/api/search?q=$encoded")
                .header("User-Agent", "MusicPlayerAndroid/1.0")
                .build()
            httpClientProvider().newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@use emptyList()
                val body = resp.body?.string().orEmpty()
                val array = JSONArray(body)
                buildList {
                    for (i in 0 until minOf(array.length(), 10)) {
                        val obj = array.getJSONObject(i)
                        val synced = obj.optString("syncedLyrics")
                        val plain = obj.optString("plainLyrics")
                        val trackName = obj.optString("trackName")
                        val artistName = obj.optString("artistName")
                        val raw = synced.ifBlank { plain }
                        if (raw.isNotBlank()) {
                            val isSynced = synced.isNotBlank()
                            val lines = if (isSynced) parseLrc(synced) else plainToLines(plain)
                            add(
                                LyricsResult(
                                    lines = lines,
                                    isSynced = isSynced,
                                    sourceLabel = "$trackName — $artistName",
                                    rawLrc = raw
                                )
                            )
                        }
                    }
                }
            }
        }.onFailure { err ->
            logger.log("LYRICS", "WARN", "Tìm kiếm lời bài hát thất bại: ${err.message}")
        }.getOrDefault(emptyList())
    }

    suspend fun saveCustomLrc(trackId: String, rawLrc: String) {
        storageManager.saveLyricsFile(trackId, rawLrc)
    }

    fun parseLrc(lrcContent: String): List<LyricLine> {
        val result = mutableListOf<LyricLine>()
        lrcContent.lines().forEach { line ->
            val matches = timeTagRegex.findAll(line).toList()
            if (matches.isEmpty()) return@forEach
            val cleanText = timeTagRegex.replace(line, "").trim()
            if (cleanText.isBlank()) return@forEach
            matches.forEach { match ->
                val min = match.groupValues[1].toLongOrNull() ?: 0L
                val sec = match.groupValues[2].toLongOrNull() ?: 0L
                val fracRaw = match.groupValues[3]
                val millis = when (fracRaw.length) {
                    0 -> 0L
                    1 -> (fracRaw.toLongOrNull() ?: 0L) * 100L
                    2 -> (fracRaw.toLongOrNull() ?: 0L) * 10L
                    else -> fracRaw.take(3).toLongOrNull() ?: 0L
                }
                val totalMs = min * 60_000L + sec * 1_000L + millis
                result += LyricLine(
                    timestampMs = totalMs,
                    text = cleanText,
                    romanization = romanizeIfNeeded(cleanText)
                )
            }
        }
        return result.sortedBy { it.timestampMs }
    }

    private fun plainToLines(plain: String): List<LyricLine> {
        return plain.lines()
            .filter { it.isNotBlank() }
            .mapIndexed { idx, line ->
                LyricLine(
                    timestampMs = idx * 4000L,
                    text = line.trim(),
                    romanization = romanizeIfNeeded(line.trim())
                )
            }
    }

    private fun fetchFromLrcLib(
        title: String,
        artist: String,
        album: String,
        durationSec: Int
    ): LyricsResult? {
        return runCatching {
            val t = URLEncoder.encode(title, "UTF-8")
            val a = URLEncoder.encode(artist, "UTF-8")
            val al = URLEncoder.encode(album, "UTF-8")
            val url = buildString {
                append("https://lrclib.net/api/get?track_name=$t&artist_name=$a")
                if (album.isNotBlank() && album != "Offline") append("&album_name=$al")
                if (durationSec > 0) append("&duration=$durationSec")
            }
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "MusicPlayerAndroid/1.0")
                .build()
            httpClientProvider().newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val obj = JSONObject(resp.body?.string() ?: return@use null)
                val synced = obj.optString("syncedLyrics")
                val plain = obj.optString("plainLyrics")
                if (synced.isNotBlank()) {
                    LyricsResult(parseLrc(synced), true, "LRCLIB (Đồng bộ)", synced)
                } else if (plain.isNotBlank()) {
                    LyricsResult(plainToLines(plain), false, "LRCLIB", plain)
                } else null
            }
        }.getOrNull()
    }

    fun romanizeIfNeeded(text: String): String? {
        // Basic Hangul syllable / Japanese Kana transliteration check so non-Latin lines get helpful romanization
        val hasHangul = text.any { it in '\uAC00'..'\uD7A3' }
        val hasKana = text.any { it in '\u3040'..'\u30FF' }
        if (!hasHangul && !hasKana) return null
        return buildString {
            text.forEach { ch ->
                when (ch) {
                    in '\uAC00'..'\uD7A3' -> append(hangulSyllableToRoman(ch))
                    in '\u3041'..'\u3096' -> append(hiraganaToRoman(ch))
                    else -> append(ch)
                }
            }
        }.trim().takeIf { it.isNotBlank() && it != text }
    }

    private fun hangulSyllableToRoman(ch: Char): String {
        val initials = arrayOf("g", "kk", "n", "d", "tt", "r", "m", "b", "pp", "s", "ss", "", "j", "jj", "ch", "k", "t", "p", "h")
        val medials = arrayOf("a", "ae", "ya", "yae", "eo", "e", "yeo", "ye", "o", "wa", "wae", "oe", "yo", "u", "wo", "we", "wi", "yu", "eu", "ui", "i")
        val finals = arrayOf("", "k", "k", "ks", "n", "nj", "nh", "t", "l", "lk", "lm", "lb", "ls", "lt", "lp", "lh", "m", "p", "ps", "t", "t", "ng", "t", "t", "k", "t", "p", "h")
        val code = ch.code - 0xAC00
        val i = code / (21 * 28)
        val m = (code % (21 * 28)) / 28
        val f = code % 28
        return initials[i] + medials[m] + finals[f]
    }

    private fun hiraganaToRoman(ch: Char): String {
        return when (ch) {
            'あ' -> "a"; 'い' -> "i"; 'う' -> "u"; 'え' -> "e"; 'お' -> "o"
            'か' -> "ka"; 'き' -> "ki"; 'く' -> "ku"; 'け' -> "ke"; 'こ' -> "ko"
            'さ' -> "sa"; 'し' -> "shi"; 'す' -> "su"; 'せ' -> "se"; 'そ' -> "so"
            'た' -> "ta"; 'ち' -> "chi"; 'つ' -> "tsu"; 'て' -> "te"; 'と' -> "to"
            'な' -> "na"; 'に' -> "ni"; 'ぬ' -> "nu"; 'ね' -> "ne"; 'の' -> "no"
            'は' -> "ha"; 'ひ' -> "hi"; 'ふ' -> "fu"; 'へ' -> "he"; 'ほ' -> "ho"
            'ま' -> "ma"; 'み' -> "mi"; 'む' -> "mu"; 'め' -> "me"; 'も' -> "mo"
            'ら' -> "ra"; 'り' -> "ri"; 'る' -> "ru"; 'れ' -> "re"; 'ろ' -> "ro"
            'わ' -> "wa"; 'を' -> "o"; 'ん' -> "n"
            else -> ch.toString()
        }
    }
}
