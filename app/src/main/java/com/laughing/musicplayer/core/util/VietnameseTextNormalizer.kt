package com.laughing.musicplayer.core.util

import java.text.Normalizer

object VietnameseTextNormalizer {
    private val combiningMarksRegex = Regex("\\p{InCombiningDiacriticalMarks}+")

    fun normalize(input: String): String {
        if (input.isBlank()) return ""
        val decomposed = Normalizer.normalize(input, Normalizer.Form.NFD)
        return combiningMarksRegex.replace(decomposed, "")
            .replace('đ', 'd')
            .replace('Đ', 'D')
            .lowercase()
            .trim()
    }

    fun matches(haystack: String, query: String): Boolean {
        val normQuery = normalize(query)
        if (normQuery.isEmpty()) return true
        val normHaystack = normalize(haystack)
        if (normHaystack.contains(normQuery)) return true
        val tokens = normQuery.split(' ').filter { it.isNotBlank() }
        return tokens.all { normHaystack.contains(it) }
    }
}
