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
            " [data-sc-content=forms]," +
            " .tag, .tag-chip, [class*=tag]"
        ).remove()
        // Drop POS chips like <span data-sc-code="n">noun</span>
        doc.select("[data-sc-code]").remove()

        // Remove known non-gloss sections like forms/reading tables that sometimes come as plain lists/tables
        run {
            val headingRe = Regex("(?i)^(forms?|priority\\s*form|special\\s*reading|readings?|conjugations?|inflections?|declensions?|variants?)$")
            val toRemove = mutableListOf<org.jsoup.nodes.Element>()
            for (el in doc.select("*")) {
                val own = el.ownText().trim()
                if (own.isNotEmpty() && headingRe.matches(own)) {
                    toRemove += el
                    el.nextElementSibling()?.let { sib ->
                        if (sib.tagName() in listOf("ul", "ol", "table", "dl")) toRemove += sib
                    }
                }
            }
            toRemove.forEach { it.remove() }
        }

        // POS/label dictionary (expanded)
        val POS_WORDS = setOf(
            "noun", "verb", "adjective", "adverb", "interjection", "conjunction", "pronoun", "preposition", "determiner", "particle",
            "prefix", "suffix", "counter", "auxiliary", "copula",
            // JP-specific markers
            "suru", "transitive", "intransitive", "ichidan", "godan", "na-adjective", "no-adjective", "i-adjective",
            // numeric dan spelling seen on Jitendex
            "5-dan",
            // usage/style
            "polite", "humble", "honorific", "slang", "colloquial", "abbr", "abbreviation", "archaic", "archaism", "figurative", "idiomatic",
            // non-gloss labels sometimes inline
            "priority", "priority form", "special reading", "reading", "form", "forms"
        )
        val posAlternation = POS_WORDS.joinToString("|") { Regex.escape(it) }
        val leadingPosSepRe = Regex("^(?:$posAlternation)(?:[\\s,;/・／-]+(?:$posAlternation))*", RegexOption.IGNORE_CASE)
        val leadingPosConcatRe = Regex("^(?:$posAlternation)+", RegexOption.IGNORE_CASE)
        val seeAlsoRe = Regex("(?i)^see also\\b.*")

        fun dropLeadingPosJargon(s: String): String {
            var t = s.trim()
            if (t.isEmpty()) return t
            if (seeAlsoRe.matches(t)) return ""
            // Preserve qualifiers like "noun〔妻 only〕" at the start:
            val qualMatch = Regex("^(?:$posAlternation)\\s*[〔\\[][^^〕\\]]+[〕\\]]", RegexOption.IGNORE_CASE).find(t)
            if (qualMatch != null) {
                val head = qualMatch.value.trim()
                val rest = t.substring(qualMatch.range.last + 1).trim()
                return (head + (if (rest.isNotEmpty()) " " + rest else "")).trim()
            }
            // Otherwise strip leading POS chips/words
            t = t.replace("\\([^)]*\\)".toRegex(), " ").trim()
            // Special-case: numeric dan + transitivity labels merged (e.g., "5-danintransitive")
            t = t.replace("^(?i)5-dan(?:\\s*|-)?(?:intransitive|transitive)".toRegex(), "").trim()
            t = t.replace(leadingPosSepRe, "").trim()
            t = t.replace(leadingPosConcatRe, "").trim()
            t = t.replace("\\s+".toRegex(), " ").trim()
            return t
        }

        // Normalize a raw token
        fun normalizeToken(s: String): String {
            var t = s
                .replace('\u00A0', ' ')
                .replace("\\s+".toRegex(), " ")
                .trim()
            // Drop vendor/date and citations
            t = t.replace("(?i)\\b(?:jitendex\\.org|jitendex|jmdict|tatoeba)\\b.*$".toRegex(), " ")
            // Keep Japanese corner-bracket notes 〔...〕 and […], but drop date stamps and stars in brackets
            t = t.replace("\\[(?:\u2605|\\*).*?]".toRegex(), " ") // [★...] or [*...]
            t = t.replace("\\[\\d{4}-\\d{2}-\\d{2}]".toRegex(), " ") // [YYYY-MM-DD]
            // Drop round parentheticals like (of buildings)
            t = t.replace("\\([^)]*\\)".toRegex(), " ").trim()
            // Drop trailing footnote refs like [1], [2]
            t = t.replace("\\[\\d+]".toRegex(), " ")
            // Trim punctuation and slashes
            t = t.trim(' ', ',', ';', ':', '-', '·', '•', '/', '\\')
            t = t.replace("\\s+".toRegex(), " ").trim()
            return t
        }

        fun isPosJargon(piece: String): Boolean {
            val cleaned = piece.lowercase().replace("[^a-z- ]".toRegex(), " ")
            val words = cleaned.split(" ").filter { it.isNotBlank() }
            if (words.isEmpty()) return false
            return words.all { it in POS_WORDS }
        }

        fun splitIntoGlosses(token: String): List<String> {
            if (token.isBlank()) return emptyList()
            val primary = token.split("|", ",", ";", "／", "/", "・", "、", " and ", " or ")
                .map { normalizeToken(it) }
                .filter { it.isNotBlank() }
                .toMutableList()
            val out = mutableListOf<String>()
            for (p in primary) {
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

        // Pattern A: JMdict/AnkiWeb/Jitendex group: take first item of each glossary list within .dict-group__glossary
        val topLis = doc.select("ol > li")
        if (topLis.isNotEmpty()) {
            for (li in topLis) {
                val glossEl = li.selectFirst(".dict-group__glossary")
                if (glossEl != null) {
                    val lists = glossEl.select("ul[data-sc-content=glossary]")
                    if (lists.isNotEmpty()) {
                        for (ul in lists) {
                            val first = ul.selectFirst("> li")?.text()?.let { normalizeToken(it) } ?: continue
                            val token = dropLeadingPosJargon(first)
                            if (token.isNotBlank()) out += token
                        }
                    } else {
                        // Fallback: old behavior, but will later keep only ASCII-letter tokens
                        val raw = glossEl.text()
                        if (raw.isNotBlank()) {
                            val head = dropLeadingPosJargon(normalizeToken(raw))
                            // Keep only the first gloss from the split for this group
                            val first = splitIntoGlosses(head).firstOrNull()
                            if (first != null && !isPosJargon(first)) out += first
                        }
                    }
                }
            }
        }

        // Pattern B: Jitendex-like: gather first LI of each glossary UL
        val glossaryLists = doc.select("ul[data-sc-content=glossary]")
        if (glossaryLists.isNotEmpty()) {
            for (ul in glossaryLists) {
                val first = ul.selectFirst("> li")?.text() ?: continue
                val token = dropLeadingPosJargon(normalizeToken(first))
                if (token.isNotBlank() && !isPosJargon(token)) out += token
            }
        }

        // Final cleanup: drop empties, dedupe, and short garbage; keep only tokens with ASCII letters to avoid JP forms tables
        val final = out.map { normalizeToken(it) }
            .filter { it.isNotBlank() && it.contains(Regex("[A-Za-z]")) && !seeAlsoRe.matches(it) }
            .toList()

        if (final.isNotEmpty()) return final.joinToString(", ")

        // Fallbacks
        val simplified = extractSimplifiedDefinition(html)
        val head = dropLeadingPosJargon(simplified)
        val parts = splitIntoGlosses(head).filter { it.isNotBlank() && !isPosJargon(it) && it.contains(Regex("[A-Za-z]")) }
        if (parts.isNotEmpty()) return parts.joinToString(", ")
        return simplified.take(120).trim()
    }
}


