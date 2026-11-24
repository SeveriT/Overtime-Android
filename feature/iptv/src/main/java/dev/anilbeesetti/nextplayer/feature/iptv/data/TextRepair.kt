package dev.anilbeesetti.nextplayer.feature.iptv.data

/**
 * Repairs text that an IPTV provider mangled before sending it (same code as the Windows app).
 * Three known kinds, all reversible:
 *  - UTF-8 read as Korean (EUC-KR) and saved again: "Jyväskylä" arrives as "Jyv채skyl채"
 *    (ä = bytes C3 A4 = 채). Only Korean letters touching a Latin letter are changed, so real
 *    Korean names stay as they are.
 *  - UTF-8 read as Latin-1 and saved again: "ä" arrives as "Ã¤".
 *  - Windows-1252 punctuation read as Latin-1: dashes and quotes arrive as invisible control
 *    characters (U+0080–U+009F).
 * Text without any of these is returned as is (the same String).
 */
object TextRepair {
    // UTF-8 bytes C3 A1..C3 BF ("á".."ÿ") read as EUC-KR, and the letters they should be
    private const val MANGLED =
        "찼창찾채책챈챌챔챕챗챘챙챠챤챦" +
            "챨챰챵처척천철첨첩첫첬청체첵첸첼"
    private const val LATIN =
        "áâãäåæçèéêëìíîï" +
            "ðñòóôõö÷øùúûüýþÿ"

    // Windows-1252 0x80..0x9F; '\u0000' = undefined there (dropped)
    private const val CP1252 =
        "€\u0000‚ƒ„…†‡ˆ‰Š‹Œ\u0000Ž\u0000" +
            "\u0000‘’“”•–—˜™š›œ\u0000žŸ"

    fun repair(text: String): String {
        if (text.none { it.code >= 0x80 }) return text
        val out = fixKorean(fixCp1252(fixDoubleUtf8(text)))
        return if (out == text) text else out
    }

    /** "Ã¤" → "ä": a Ã/Â followed by a continuation byte (U+0080–U+00BF) is one UTF-8 letter. */
    private fun fixDoubleUtf8(text: String): String {
        if (text.none { it == 'Ã' || it == 'Â' }) return text
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val next = text.getOrNull(i + 1)
            if ((c == 'Ã' || c == 'Â') && next != null && next.code in 0x80..0xBF) {
                sb.append((((c.code and 0x1F) shl 6) or (next.code and 0x3F)).toChar())
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    private fun fixCp1252(text: String): String {
        if (text.none { it.code in 0x80..0x9F }) return text
        val sb = StringBuilder(text.length)
        for (c in text) {
            if (c.code in 0x80..0x9F) {
                val mapped = CP1252[c.code - 0x80]
                if (mapped != '\u0000') sb.append(mapped)
            } else {
                sb.append(c)
            }
        }
        return sb.toString()
    }

    /** A run of mangled Korean letters is repaired when it touches a Latin letter on either side. */
    private fun fixKorean(text: String): String {
        if (text.none { it in MANGLED }) return text
        val sb = StringBuilder(text)
        var i = 0
        while (i < sb.length) {
            if (sb[i] !in MANGLED) {
                i++
                continue
            }
            var end = i
            while (end < sb.length && sb[end] in MANGLED) end++
            val before = sb.getOrNull(i - 1)
            val after = sb.getOrNull(end)
            if ((before != null && before.isLatinLetter()) ||
                (after != null && after.isLatinLetter())
            ) {
                for (k in i until end) {
                    sb.setCharAt(
                        k,
                        LATIN[MANGLED.indexOf(sb[k])],
                    )
                }
            }
            i = end
        }
        return sb.toString()
    }

    private fun Char.isLatinLetter() = this in 'a'..'z' || this in 'A'..'Z' || code in 0xC0..0x24F
}
