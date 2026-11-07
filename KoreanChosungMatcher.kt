package app.prolauncher.ai

/**
 * Supports fast Korean Chosung (초성) and substring matching for minimalist launcher search.
 * Example: "ㅋㅋㅇㅌ" matches "카카오톡", "ㅇㅌㅂ" matches "유튜브".
 */
object KoreanChosungMatcher {
    private val CHOSUNG = charArrayOf(
        'ㄱ', 'ㄲ', 'ㄴ', 'ㄷ', 'ㄸ', 'ㄹ', 'ㅁ', 'ㅂ', 'ㅃ',
        'ㅅ', 'ㅆ', 'ㅇ', 'ㅈ', 'ㅉ', 'ㅊ', 'ㅋ', 'ㅌ', 'ㅍ', 'ㅎ'
    )

    fun extractChosung(text: String): String {
        val sb = StringBuilder()
        for (ch in text) {
            if (ch in '가'..'힣') {
                val uniVal = ch.code - 0xAC00
                val choIdx = uniVal / (21 * 28)
                sb.append(CHOSUNG[choIdx])
            } else {
                sb.append(ch.lowercaseChar())
            }
        }
        return sb.toString()
    }

    fun matches(target: String, rawQuery: String): Boolean {
        val query = rawQuery.trim().lowercase()
        if (query.isEmpty()) return true
        val normalizedTarget = target.lowercase().replace(" ", "")
        val normalizedQuery = query.replace(" ", "")
        if (normalizedTarget.contains(normalizedQuery)) return true
        val targetChosung = extractChosung(normalizedTarget)
        return targetChosung.contains(normalizedQuery)
    }
}
