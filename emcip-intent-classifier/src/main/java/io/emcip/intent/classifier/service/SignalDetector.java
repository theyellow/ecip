package io.emcip.intent.classifier.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Structural and script-based signal detectors. All detectors run on every message regardless of
 * lexical rule results. Scores are always returned; callers decide how to use them.
 */
@Component
class SignalDetector {

    // Cyrillic characters that are visually identical to Latin letters
    private static final Set<Integer> CYRILLIC_LOOKALIKES =
            Set.of(
                    0x0430, 0x0435, 0x043E, 0x0440, 0x0441, 0x0445, 0x0443, // а е о р с х у
                    0x0412, 0x041D, 0x041A, 0x041C, 0x0422, 0x0420, 0x0410, // В Н К М Т Р А
                    0x0415, 0x041E, 0x0421, 0x0425); // Е О С Х

    // Greek characters that are visually identical to Latin letters
    private static final Set<Integer> GREEK_LOOKALIKES =
            Set.of(
                    0x0391, 0x0392, 0x0395, 0x0396, 0x0397, 0x0399, 0x039A, // Α Β Ε Ζ Η Ι Κ
                    0x039C, 0x039D, 0x039F, 0x03A1, 0x03A4, 0x03A5, 0x03A7, // Μ Ν Ο Ρ Τ Υ Χ
                    0x03BF, 0x03C1, 0x03BD, 0x03B1, 0x03B5, 0x03BA, 0x03C4, // ο ρ ν α ε κ τ
                    0x03C7, 0x03C5); // χ υ

    private static final Set<Integer> ALL_LOOKALIKES;

    static {
        Set<Integer> all = new HashSet<>(CYRILLIC_LOOKALIKES);
        all.addAll(GREEK_LOOKALIKES);
        ALL_LOOKALIKES = Collections.unmodifiableSet(all);
    }

    // Zero-width, invisible, and direction-override characters
    private static final Set<Integer> ZERO_WIDTH_CHARS =
            Set.of(
                    0x200B, 0x200C, 0x200D, 0xFEFF, 0x00AD, 0x2060, 0x2061, 0x2062, 0x2063,
                    0x2064, // invisible operators
                    0x202A, 0x202B, 0x202C, 0x202D, 0x202E, // directional embedding/override
                    0x2066, 0x2067, 0x2068, 0x2069, // directional isolate
                    0x200E, 0x200F); // LRM / RLM

    // Content types that trigger imageOnly (non-sticker media with blank caption)
    private static final Set<String> IMAGE_CONTENT_TYPES =
            Set.of(
                    "photo",
                    "video",
                    "animation",
                    "document",
                    "audio",
                    "voice",
                    "video_note",
                    "other");

    // Small, high-precision toxicity list. MUST use whole-word \b matching.
    // Whole-word matching prevents false positives on German compounds
    // (e.g. "arschloch" inside a compound word won't match standalone \barschloch\b).
    private static final List<Pattern> TOXICITY_PATTERNS = buildToxicityPatterns();

    private static List<Pattern> buildToxicityPatterns() {
        String[] terms = {
            "nigger",
            "nigga",
            "faggot",
            "cunt",
            "kike",
            "spic",
            "chink",
            "wetback",
            "gook",
            "towelhead",
            "raghead",
            "hurensohn",
            "wichser",
            "fotze",
            "arschloch"
        };
        List<Pattern> patterns = new ArrayList<>(terms.length);
        for (String term : terms) {
            patterns.add(
                    Pattern.compile(
                            "\\b" + Pattern.quote(term) + "\\b",
                            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS));
        }
        return patterns;
    }

    /**
     * Run all signal detectors on the message text and metadata. Always returns a map containing
     * all nine signal keys; never omits a key.
     */
    Map<String, Object> detect(String text, Map<String, Object> metadata) {
        String t = text != null ? text : "";
        Map<String, Object> scores = new LinkedHashMap<>();

        computeForeignScript(t, scores);
        scores.put("lookalikeSuspicion", computeLookalikeSuspicion(t));
        scores.put("zeroWidthAbuse", detectZeroWidth(t));
        scores.put("capsRatio", computeCapsRatio(t));
        scores.put("emojiOnly", isEmojiOnly(t));

        String contentType = metadata != null ? (String) metadata.get("contentType") : null;
        boolean stickerOnly = "sticker".equals(contentType);
        scores.put("stickerOnly", stickerOnly);

        boolean imageOnly =
                !stickerOnly
                        && t.isBlank()
                        && (contentType == null || IMAGE_CONTENT_TYPES.contains(contentType));
        scores.put("imageOnly", imageOnly);

        scores.put("toxicityHint", computeToxicityHint(t));

        return scores;
    }

    private void computeForeignScript(String text, Map<String, Object> scores) {
        int totalLetters = 0;
        int foreignLetters = 0;
        int cyrillicLetters = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (!Character.isLetter(cp)) continue;
            totalLetters++;
            if (isForeignScript(cp)) {
                foreignLetters++;
                if (cp >= 0x0400 && cp <= 0x04FF) cyrillicLetters++;
            }
        }
        double foreignRatio = totalLetters > 0 ? (double) foreignLetters / totalLetters : 0.0;
        double cyrillicRatio = totalLetters > 0 ? (double) cyrillicLetters / totalLetters : 0.0;
        scores.put("foreignScriptRatio", foreignRatio);
        scores.put("cyrillicRatio", cyrillicRatio);
    }

    /** Returns true if the codepoint is in a non-Latin script block and NOT a lookalike char. */
    private boolean isForeignScript(int cp) {
        if (ALL_LOOKALIKES.contains(cp)) return false;
        return (cp >= 0x0400 && cp <= 0x04FF)
                || (cp >= 0x0600 && cp <= 0x06FF)
                || (cp >= 0x4E00 && cp <= 0x9FFF)
                || (cp >= 0x0900 && cp <= 0x097F)
                || (cp >= 0x0370 && cp <= 0x03FF);
    }

    private double computeLookalikeSuspicion(String text) {
        String[] words = text.split("[^\\p{L}]+");
        int totalWords = 0;
        int suspiciousWords = 0;
        for (String word : words) {
            if (word.isEmpty()) continue;
            totalWords++;
            boolean hasLookalike = false;
            boolean hasLatin = false;
            for (int i = 0; i < word.length(); ) {
                int cp = word.codePointAt(i);
                i += Character.charCount(cp);
                if (ALL_LOOKALIKES.contains(cp)) {
                    hasLookalike = true;
                } else if (Character.isLetter(cp) && cp < 0x0300) {
                    // cp < 0x0300 covers Basic Latin + Latin Extended (incl. ä ö ü Ä Ö Ü ß)
                    hasLatin = true;
                }
            }
            if (hasLookalike && hasLatin) suspiciousWords++;
        }
        return totalWords > 0 ? (double) suspiciousWords / totalWords : 0.0;
    }

    private boolean detectZeroWidth(String text) {
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (ZERO_WIDTH_CHARS.contains(cp)) return true;
        }
        return false;
    }

    private double computeCapsRatio(String text) {
        int total = 0;
        int upper = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isLetter(cp)) {
                total++;
                if (Character.isUpperCase(cp)) upper++;
            }
        }
        if (total < 5) return 0.0;
        return (double) upper / total;
    }

    private boolean isEmojiOnly(String text) {
        if (text.isBlank()) return false;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isWhitespace(cp)) continue;
            if (!isEmojiCodePoint(cp)) return false;
        }
        return true;
    }

    private static boolean isEmojiCodePoint(int cp) {
        return (cp >= 0x1F300 && cp <= 0x1FAFF)
                || (cp >= 0x2600 && cp <= 0x27BF)
                || (cp >= 0xFE00 && cp <= 0xFE0F)
                || (cp >= 0x1F000 && cp <= 0x1F02F)
                || cp == 0x200D; // ZWJ in emoji sequences
    }

    private double computeToxicityHint(String text) {
        if (text.isBlank()) return 0.0;
        int matches = 0;
        for (Pattern p : TOXICITY_PATTERNS) {
            var matcher = p.matcher(text);
            while (matcher.find()) matches++;
        }
        String[] words = text.split("\\s+");
        int wordCount = words.length;
        return wordCount > 0 ? Math.min(1.0, (double) matches / wordCount) : 0.0;
    }
}
