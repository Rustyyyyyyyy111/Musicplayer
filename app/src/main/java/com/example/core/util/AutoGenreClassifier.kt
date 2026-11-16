package com.example.core.util

import com.example.core.model.GenreType
import com.example.core.model.Track

object AutoGenreClassifier {

    fun classify(
        title: String,
        artist: String = "",
        album: String = "",
        fileName: String = "",
        sourceContext: String = "",
        metadataGenre: String? = null
    ): GenreType {
        val combined = VietnameseTextNormalizer.normalize(
            listOfNotNull(metadataGenre, title, artist, album, fileName, sourceContext)
                .joinToString(" ")
        )

        return when {
            containsAny(combined, "bolero", "nhac vang", "le quyen", "quang le", "nhu quynh", "che linh", "duy khanh") ->
                GenreType.BOLERO
            containsAny(combined, "tru tinh", "que huong", "dan ca", "cam ly", "phi nhung", "manh quynh") ->
                GenreType.TRU_TINH
            containsAny(combined, "remix", "vinahouse", "nonstop", "dj ", "mashup", "bootleg") ->
                GenreType.REMIX
            containsAny(combined, "deep house", "tech house", "tropical house", "progressive house", "house") ->
                GenreType.HOUSE
            containsAny(combined, "edm", "electronic", "dubstep", "trance", "techno", "electro", "future bass") ->
                GenreType.EDM
            containsAny(combined, "acoustic", "unplugged", "guitar", "piano cover", "mellow") ->
                GenreType.ACOUSTIC
            containsAny(combined, "rap", "hip hop", "hip-hop", "hiphop", "trap", "drill", "den vau", "binz", "mck", "tlinh", "karik") ->
                GenreType.RAP_HIPHOP
            containsAny(combined, "rock", "metal", "punk", "indie rock", "alternative", "buc tuong", "ngot", "ca hoi hoang") ->
                GenreType.ROCK
            containsAny(combined, "classical", "symphony", "sonata", "concerto", "beethoven", "mozart", "chopin", "bach", "orchestra") ->
                GenreType.CLASSICAL
            containsAny(combined, "jazz", "bossa", "swing", "sax", "blues") ->
                GenreType.JAZZ
            containsAny(combined, "ballad", "lofi", "chill", "sad", "ha anh tuan", "bui anh tuan", "trung quan", "thuy chi", "phan manh quynh") ->
                GenreType.BALLAD
            containsAny(combined, "pop", "v-pop", "vpop", "k-pop", "kpop", "us-uk", "son tung", "my tam", "mono", "amee", "hoang thuy linh") ->
                GenreType.POP
            else -> GenreType.OTHER
        }
    }

    fun classifyTrack(track: Track): GenreType {
        if (track.genreManuallyEdited) return track.genre
        return classify(
            title = track.title,
            artist = track.artist,
            album = track.album,
            fileName = track.fileName,
            sourceContext = listOfNotNull(track.telegramTopicName, track.telegramChatTitle).joinToString(" ")
        )
    }

    private fun containsAny(text: String, vararg keywords: String): Boolean {
        return keywords.any { text.contains(it) }
    }
}
