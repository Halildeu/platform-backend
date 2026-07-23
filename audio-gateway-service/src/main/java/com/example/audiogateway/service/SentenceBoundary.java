package com.example.audiogateway.service;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Turkish sentence-boundary detection over committed STT text (Faz 24 — transcript
 * readability).
 *
 * <p><b>Why this exists.</b> live-stt commits a segment on an <i>acoustic</i> boundary:
 * a forced commit every {@code STT_FORCED_COMMIT_SEC} seconds, or {@code
 * STT_SILENCE_COMMIT_SEC} (0.7 s) of silence. A speaker who pauses mid-sentence
 * therefore gets the sentence cut in half, and every cut becomes its own transcript
 * line. Whisper already emits punctuation for Turkish, so the linguistic boundary is
 * available in the text even though the acoustic boundary is not.
 *
 * <h2>Bias: when uncertain, do NOT end the sentence</h2>
 * A false positive splits a sentence — exactly the defect being fixed. A false negative
 * merely joins one more fragment onto the line, and {@link SentenceAssembler}'s bounded
 * flush guarantees the line still closes. So every ambiguous case below resolves to
 * "not a boundary".
 *
 * <h2>URLs and e-mail addresses</h2>
 * Deliberately not special-cased. Only the <i>trailing</i> character of a committed
 * fragment is inspected, and the dots inside {@code example.com} or {@code a@b.com} are
 * never in that position: a fragment ending in {@code example.com} ends with {@code m}
 * (no boundary), and one ending in {@code example.com.} ends with a real terminator.
 * Adding a URL branch here would be unreachable code.
 */
public final class SentenceBoundary {

    /**
     * Turkish abbreviations whose trailing dot is part of the word, compared
     * lower-cased with the Turkish locale so {@code No.} → {@code no} and
     * {@code VB.} → {@code vb}.
     *
     * <p>Kept to abbreviations that plausibly occur in <i>speech</i>. Padding this with
     * bibliographic forms nobody says out loud would only widen the false-negative
     * surface without buying accuracy.
     */
    private static final Set<String> ABBREVIATIONS =
            Set.of(
                    "dr", "doç", "prof", "sn", "av", "yrd", "öğr", "gör", "arş", "uzm",
                    "vb", "vs", "bkz", "örn", "no", "bşk", "müd", "gen", "alb",
                    "mah", "cad", "sok", "apt", "blv", "tel", "faks", "fax",
                    "yy", "çev", "vd", "krş", "üniv", "fak");

    /** {@code A.} / {@code T.C.} / {@code A.Ş.} — one or more single-letter initials. */
    private static final Pattern INITIALS =
            Pattern.compile("^(\\p{Lu}\\.)+$", Pattern.UNICODE_CASE);

    /** {@code 1.} / {@code 12.} — an ordinal marker ("1. madde"), not a full stop. */
    private static final Pattern ORDINAL = Pattern.compile("^\\d+\\.$");

    /**
     * {@code 3.14.} / {@code 14,30.} — a decimal or clock value followed by a real full
     * stop. The inner separator is part of the number; the trailing dot ends the
     * sentence.
     */
    private static final Pattern DECIMAL_THEN_STOP = Pattern.compile("^\\d+[.,]\\d+\\.$");

    private SentenceBoundary() {}

    /**
     * Whether {@code text} ends a sentence.
     *
     * @param text a committed transcript fragment; {@code null} and blank are not
     *     boundaries
     */
    public static boolean endsSentence(final String text) {
        if (text == null) {
            return false;
        }
        final String trimmed = text.strip();
        if (trimmed.isEmpty()) {
            return false;
        }
        final char last = trimmed.charAt(trimmed.length() - 1);
        // '?', '!' and '…' are unambiguous, and repeats ("?!", "!!!") end with one of
        // them too. Only '.' needs disambiguation.
        if (last == '?' || last == '!' || last == '…') {
            return true;
        }
        if (last != '.') {
            return false;
        }
        return isFullStop(lastToken(trimmed));
    }

    private static boolean isFullStop(final String token) {
        if (token.isEmpty()) {
            return false;
        }
        // "3.14." — the inner separator belongs to the number, the last dot terminates.
        if (DECIMAL_THEN_STOP.matcher(token).matches()) {
            return true;
        }
        if (ORDINAL.matcher(token).matches() || INITIALS.matcher(token).matches()) {
            return false;
        }
        final String word = stripTrailingDots(token).toLowerCase(Locale.forLanguageTag("tr"));
        // "bilmiyorum..." strips to a normal word and does end the sentence; "vb."
        // strips to an abbreviation and does not.
        return !ABBREVIATIONS.contains(word);
    }

    /** The last whitespace-delimited token — the only one the trailing dot can belong to. */
    private static String lastToken(final String trimmed) {
        int start = trimmed.length() - 1;
        while (start > 0 && !Character.isWhitespace(trimmed.charAt(start - 1))) {
            start--;
        }
        return trimmed.substring(start);
    }

    private static String stripTrailingDots(final String token) {
        int end = token.length();
        while (end > 0 && token.charAt(end - 1) == '.') {
            end--;
        }
        return token.substring(0, end);
    }
}
