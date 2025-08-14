package com.alcyon.glyphanki.parser

import org.jsoup.Jsoup
import org.jsoup.nodes.Document

object HtmlParser {
    private fun looksLikeHtml(s: String): Boolean {
        val t = s.trim()
        if (t.isEmpty()) return false
        // Fast heuristic: contains a tag-like pattern
        return "</?\\w+[^>]*>".toRegex().containsMatchIn(t)
    }

    fun extractJapaneseWord(html: String): String {
        if (!looksLikeHtml(html)) return html.trim()
        val doc: Document = Jsoup.parse(html)
        doc.select("script, style, audio, video").remove()
        val text = doc.body().text()
        return text.trim()
    }

    fun extractSimplifiedDefinition(html: String): String {
        if (!looksLikeHtml(html)) return html.trim()
        val doc: Document = Jsoup.parse(html)
        doc.select("script, style, audio, video, img, sup, sub").remove()
        val text = doc.body().text()
            .replace("【", " ")
            .replace("】", " ")
            .replace("(\n|\r)+".toRegex(), " ")
            .replace("\u00A0", " ")
            .trim()
        return text
    }

    fun extractTextFromHtml(html: String): String {
        if (!looksLikeHtml(html)) return html.trim()
        val doc: Document = Jsoup.parse(html)
        doc.select("script, style, audio, video").remove()
        val text = doc.body().text()
        return text.trim()
    }

    // Extract concise glosses only (e.g., "construction, architecture").
    fun extractDefinitionSummary(html: String): String {
        if (!looksLikeHtml(html)) return html.trim()
        val doc: Document = Jsoup.parse(html)
        // Remove noise: examples, xrefs, attributions, and common tag/POS chips
        doc.select(
            "script, style, audio, video, img, sup, sub," +
            " [data-sc-content=example-sentence], [data-sc-content=extra-info]," +
            " [data-sc-content=xref], [data-sc-content=xref-glossary]," +
            " [data-sc-content=attribution]," +
            " [data-sc-content=tags], [data-sc-content=tag], [data-sc-content=pos], [data-sc-content=word-class]," +
            " [data-sc-content=label], [data-sc-content=grammar], [data-sc-content=flags]," +
            " .pos, .tag, .tag-chip, [class*=pos], [class*=tag]"
        ).remove()

        // POS/label dictionary
        val POS_WORDS = setOf(
            "noun", "verb", "adjective", "adverb", "interjection", "conjunction", "pronoun", "preposition", "determiner", "particle",
            "prefix", "suffix", "counter", "auxiliary", "copula",
            // JP-specific markers
            "suru", "transitive", "intransitive", "ichidan", "godan", "na-adjective", "no-adjective", "i-adjective",
            // usage/style
            "polite", "humble", "honorific", "slang", "colloquial", "abbr", "abbreviation", "archaism", "figurative", "idiomatic"
        )
        val posAlternation = POS_WORDS.joinToString("|") { Regex.escape(it) }
        val leadingPosSepRe = Regex("^(?:$posAlternation)(?:[\\s,;/・／-]+(?:$posAlternation))*", RegexOption.IGNORE_CASE)
        val leadingPosConcatRe = Regex("^(?:$posAlternation)+", RegexOption.IGNORE_CASE)

        fun dropLeadingPosJargon(s: String): String {
            var t = s.trim()
            // Remove parentheticals early (e.g., (of buildings))
            t = t.replace("\\([^)]*\\)".toRegex(), " ").trim()
            // 1) Separated labels at the start (with spaces/commas/slashes/dots/hyphens)
            t = t.replace(leadingPosSepRe, "").trim()
            // 2) Concatenated labels like "nounsurutransitive"
            t = t.replace(leadingPosConcatRe, "").trim()
            // Collapse whitespace
            t = t.replace("\\s+".toRegex(), " ").trim()
            return t
        }

        // Normalize a raw token: drop parentheticals, trim punctuation/space, collapse whitespace
        fun normalizeToken(s: String): String {
            var t = s
                .replace("\u00A0", " ")
                .replace("\\s+".toRegex(), " ")
                .trim()
            // Drop any parenthetical notes like (of buildings)
            t = t.replace("\\([^)]*\\)".toRegex(), "").trim()
            // Trim separators we might carry
            t = t.trim(' ', ',', ';', ':', '-', '·', '•', '/', '\\')
            // Collapse again
            t = t.replace("\\s+".toRegex(), " ").trim()
            return t
        }

        fun isPosJargon(piece: String): Boolean {
            val cleaned = piece.lowercase().replace("[^a-z- ]".toRegex(), " ")
            val words = cleaned.split(" ").filter { it.isNotBlank() }
            if (words.isEmpty()) return false
            return words.all { it in POS_WORDS }
        }

        // Split a candidate into atomic glosses
        fun splitIntoGlosses(token: String): List<String> {
            if (token.isBlank()) return emptyList()
            val primary = token.split("|", ",", ";", "／", "/", "・", "、", " and ", " or ")
                .map { normalizeToken(it) }
                .filter { it.isNotBlank() }
                .toMutableList()
            val out = mutableListOf<String>()
            for (p in primary) {
                // Heuristic: if exactly two long words with a single space, split them
                val m = "^([A-Za-z]{4,}) ([A-Za-z]{4,})$".toRegex().matchEntire(p)
                if (m != null) {
                    out += m.groupValues[1]
                    out += m.groupValues[2]
                } else {
                    out += p
                }
            }
            return out
        }

        val out = linkedSetOf<String>()

        // Pattern A: JMdict/AnkiWeb: take the text from .dict-group__glossary within each top <li>
        val topLis = doc.select("ol > li")
        if (topLis.isNotEmpty()) {
            for (li in topLis) {
                val glossEl = li.selectFirst(".dict-group__glossary")
                if (glossEl != null) {
                    val raw = glossEl.text()
                    if (raw.isNotBlank()) {
                        val head = dropLeadingPosJargon(normalizeToken(raw))
                        splitIntoGlosses(head).forEach { g -> if (!isPosJargon(g)) out += g }
                    }
                }
            }
        }

        // Pattern B: Jitendex-like: gather items from glossary lists
        val glossaryLists = doc.select("ul[data-sc-content=glossary]")
        if (glossaryLists.isNotEmpty()) {
            for (ul in glossaryLists) {
                val lis = ul.select("> li")
                for (li in lis) {
                    val candidates = li.select("[data-sc-content=gloss], .gloss")
                    if (candidates.isNotEmpty()) {
                        for (el in candidates) {
                            val token = dropLeadingPosJargon(normalizeToken(el.text()))
                            splitIntoGlosses(token).forEach { g -> if (!isPosJargon(g)) out += g }
                        }
                    } else {
                        val cleaned = li.clone()
                        cleaned.select("[data-sc-content=tags], [data-sc-content=pos], [data-sc-content=word-class], .pos, .tag, .tag-chip, [class*=pos], [class*=tag]").remove()
                        val raw = cleaned.text()
                        val token = dropLeadingPosJargon(normalizeToken(raw))
                        splitIntoGlosses(token).forEach { g -> if (!isPosJargon(g)) out += g }
                    }
                }
            }
        }

        // Final cleanup: drop empties, dedupe, and short garbage
        val final = out.map { normalizeToken(it) }
            .filter { it.isNotBlank() && it.any { ch -> ch.isLetter() } }

        if (final.isNotEmpty()) return final.joinToString(", ")

        // Fallbacks
        val simplified = extractSimplifiedDefinition(html)
        val head = dropLeadingPosJargon(simplified)
        val parts = splitIntoGlosses(head).filter { it.isNotBlank() && !isPosJargon(it) }
        if (parts.isNotEmpty()) return parts.joinToString(", ")
        return simplified.take(120).trim()
    }
}


