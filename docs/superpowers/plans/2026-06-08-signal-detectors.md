# Signal Detectors Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add 9 signal detectors (structural/script-based abuse patterns) that enrich every classified message with numeric scores, assign new primary intents when lexical rules don't match, and forward those scores through to `PolicyDecisionEvent`.

**Architecture:** Three modules change: (1) TDLib adapter adds `contentType` to message metadata; (2) intent-classifier gains a package-private `SignalDetector` class wired into `IntentClassificationService.classify()`; (3) policy-engine forwards the new signal param keys from `IntentClassifiedEvent.parameters` into `PolicyDecision.metadata` and `PolicyDecisionEvent.context`. No new DB tables, no new Kafka topics.

**Tech Stack:** Java 21, Spring Boot 4, JUnit 5, AssertJ, Mockito

**Spec:** `docs/superpowers/specs/2026-06-08-signal-detectors-design.md`

---

## File Map

| File | Action | What changes |
|---|---|---|
| `emcip-tdlib-adapter/src/main/java/io/emcip/tdlib/adapter/service/TelegramEventPublisher.java` | Modify | Add `contentType` switch in `extractMetadata()` |
| `emcip-tdlib-adapter/src/test/java/io/emcip/tdlib/adapter/service/TelegramEventPublisherTest.java` | Modify | Add 3 contentType tests + 2 helpers |
| `emcip-intent-classifier/src/main/java/io/emcip/intent/classifier/service/SignalDetector.java` | **Create** | New package-private class — 9 detectors |
| `emcip-intent-classifier/src/test/java/io/emcip/intent/classifier/service/SignalDetectorTest.java` | **Create** | Full unit test suite for SignalDetector |
| `emcip-intent-classifier/src/main/java/io/emcip/intent/classifier/service/IntentClassificationService.java` | Modify | Wire SignalDetector; replace intent loop with priority chain |
| `emcip-intent-classifier/src/test/java/io/emcip/intent/classifier/service/IntentClassificationServiceTest.java` | Modify | Add signal-intent tests; verify existing tests still pass |
| `emcip-policy-engine/src/main/java/io/emcip/policy/engine/service/PolicyEvaluationService.java` | Modify | Add `SIGNAL_PARAM_KEYS`; forward scores in `persistDecision()` and the `PolicyDecisionEvent` context |
| `emcip-policy-engine/src/test/java/io/emcip/policy/engine/service/PolicyEvaluationServiceTest.java` | Modify | Add signal-forwarding assertion test |

---

## Pre-flight: create feature branch

```bash
git checkout main && git pull
git checkout -b feat/signal-detectors
```

---

## Task 1: TDLib Adapter — `contentType` enrichment

`extractMetadata()` currently returns no `contentType` for non-text messages. All other content types produce `text=""` with no metadata, making stickers and photos indistinguishable at the classifier.

**Files:**
- Modify: `emcip-tdlib-adapter/src/main/java/io/emcip/tdlib/adapter/service/TelegramEventPublisher.java:212-225`
- Modify: `emcip-tdlib-adapter/src/test/java/io/emcip/tdlib/adapter/service/TelegramEventPublisherTest.java`

- [ ] **Step 1: Write failing tests**

Add to `TelegramEventPublisherTest.java`. You need these new imports: `import static org.assertj.core.api.Assertions.assertThat;`, `import static org.mockito.Mockito.atLeastOnce;`, `import org.mockito.ArgumentCaptor;`.

```java
@Test
void extractMetadata_messageText_setsContentTypeText() {
    TdApi.UpdateNewMessage update = makeUpdate(100L, 3L, "hello");

    StepVerifier.create(publisher.publishMessage(update.message, update, null))
            .verifyComplete();

    @SuppressWarnings("unchecked")
    ArgumentCaptor<org.apache.kafka.clients.producer.ProducerRecord<String, String>> captor =
            ArgumentCaptor.forClass(org.apache.kafka.clients.producer.ProducerRecord.class);
    verify(kafkaTemplate, atLeastOnce()).send(captor.capture());
    assertThat(captor.getValue().value()).contains("\"contentType\":\"text\"");
}

@Test
void extractMetadata_messageSticker_setsContentTypeSticker() {
    TdApi.UpdateNewMessage update = makeStickerUpdate(100L, 4L);

    StepVerifier.create(publisher.publishMessage(update.message, update, null))
            .verifyComplete();

    @SuppressWarnings("unchecked")
    ArgumentCaptor<org.apache.kafka.clients.producer.ProducerRecord<String, String>> captor =
            ArgumentCaptor.forClass(org.apache.kafka.clients.producer.ProducerRecord.class);
    verify(kafkaTemplate, atLeastOnce()).send(captor.capture());
    assertThat(captor.getValue().value()).contains("\"contentType\":\"sticker\"");
}

@Test
void extractMetadata_messagePhoto_setsContentTypePhoto() {
    TdApi.UpdateNewMessage update = makePhotoUpdate(100L, 5L);

    StepVerifier.create(publisher.publishMessage(update.message, update, null))
            .verifyComplete();

    @SuppressWarnings("unchecked")
    ArgumentCaptor<org.apache.kafka.clients.producer.ProducerRecord<String, String>> captor =
            ArgumentCaptor.forClass(org.apache.kafka.clients.producer.ProducerRecord.class);
    verify(kafkaTemplate, atLeastOnce()).send(captor.capture());
    assertThat(captor.getValue().value()).contains("\"contentType\":\"photo\"");
}

private TdApi.UpdateNewMessage makeStickerUpdate(long chatId, long messageId) {
    TdApi.MessageSticker content = new TdApi.MessageSticker();
    TdApi.Message message = new TdApi.Message();
    message.id = messageId;
    message.chatId = chatId;
    message.content = content;
    TdApi.UpdateNewMessage update = new TdApi.UpdateNewMessage();
    update.message = message;
    return update;
}

private TdApi.UpdateNewMessage makePhotoUpdate(long chatId, long messageId) {
    TdApi.MessagePhoto content = new TdApi.MessagePhoto();
    TdApi.Message message = new TdApi.Message();
    message.id = messageId;
    message.chatId = chatId;
    message.content = content;
    TdApi.UpdateNewMessage update = new TdApi.UpdateNewMessage();
    update.message = message;
    return update;
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
cd /path/to/ecip
mvn -pl emcip-tdlib-adapter test -q 2>&1 | tail -30
```

Expected: 3 new tests fail (no `contentType` key in JSON yet).

- [ ] **Step 3: Implement the switch in `extractMetadata()`**

In `TelegramEventPublisher.java`, replace the current `extractMetadata()` body (lines 212–225) with:

```java
private java.util.Map<String, Object> extractMetadata(TdApi.Message message) {
    java.util.Map<String, Object> metadata = new java.util.HashMap<>();

    if (message.content instanceof TdApi.MessageText messageText) {
        metadata.put("textLength", messageText.text.text.length());
        if (messageText.text.entities != null) {
            metadata.put("entityCount", messageText.text.entities.length);
        }
    }

    metadata.put("isChannelPost", message.isChannelPost);

    String contentType =
            switch (message.content) {
                case TdApi.MessageText ignored -> "text";
                case TdApi.MessageSticker ignored -> "sticker";
                case TdApi.MessagePhoto ignored -> "photo";
                case TdApi.MessageVideo ignored -> "video";
                case TdApi.MessageAnimation ignored -> "animation";
                case TdApi.MessageDocument ignored -> "document";
                case TdApi.MessageAudio ignored -> "audio";
                case TdApi.MessageVoiceNote ignored -> "voice";
                case TdApi.MessageVideoNote ignored -> "video_note";
                case TdApi.MessagePoll ignored -> "poll";
                default -> "other";
            };
    metadata.put("contentType", contentType);

    return metadata;
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
mvn -pl emcip-tdlib-adapter test -q 2>&1 | tail -20
```

Expected: all tests pass including the 3 new ones.

- [ ] **Step 5: Apply Spotless and commit**

```bash
mvn -pl emcip-tdlib-adapter spotless:apply
git add emcip-tdlib-adapter/src/
git commit -m "feat(tdlib-adapter): add contentType to extractMetadata() for all message types"
```

---

## Task 2: `SignalDetector` class

A new package-private class in `io.emcip.intent.classifier.service`. No Spring annotations — plain Java, no dependencies.

**Files:**
- **Create:** `emcip-intent-classifier/src/main/java/io/emcip/intent/classifier/service/SignalDetector.java`
- **Create:** `emcip-intent-classifier/src/test/java/io/emcip/intent/classifier/service/SignalDetectorTest.java`

- [ ] **Step 1: Write `SignalDetectorTest.java`**

```java
package io.emcip.intent.classifier.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SignalDetectorTest {

    private SignalDetector detector;

    @BeforeEach
    void setUp() {
        detector = new SignalDetector();
    }

    // --- foreignScriptRatio + cyrillicRatio ---

    @Test
    void foreignScript_pureCyrillic_ratioPproachesOne() {
        Map<String, Object> r = detector.detect("Привет мир", null);
        assertThat((Double) r.get("foreignScriptRatio")).isGreaterThanOrEqualTo(0.6);
        assertThat((Double) r.get("cyrillicRatio")).isGreaterThan(0.0);
    }

    @Test
    void foreignScript_pureGreek_ratioAboveThreshold() {
        // "Γεια σου" = "Hello you" in Greek
        Map<String, Object> r = detector.detect("Γεια σου φίλε", null);
        assertThat((Double) r.get("foreignScriptRatio")).isGreaterThan(0.0);
    }

    @Test
    void foreignScript_latinText_ratioIsZero() {
        Map<String, Object> r = detector.detect("Hello world", null);
        assertThat(r.get("foreignScriptRatio")).isEqualTo(0.0);
        assertThat(r.get("cyrillicRatio")).isEqualTo(0.0);
    }

    @Test
    void foreignScript_germanUmlauts_ratioIsZero() {
        // ä ö ü Ä Ö Ü ß are Latin Extended (U+00C0–U+024F) — NOT foreign script
        Map<String, Object> r = detector.detect("Über die Größe der Öffnung", null);
        assertThat(r.get("foreignScriptRatio")).isEqualTo(0.0);
    }

    // --- lookalikeSuspicion ---

    @Test
    void lookalike_cyrillicMixedWord_suspicionGreaterThanZero() {
        // "hеllo" — е is Cyrillic U+0435, the rest are Latin
        Map<String, Object> r = detector.detect("h\u0435llo", null);
        assertThat((Double) r.get("lookalikeSuspicion")).isGreaterThan(0.0);
    }

    @Test
    void lookalike_greekMixedWord_suspicionGreaterThanZero() {
        // "hΑllo" — Α is Greek U+0391, the rest are Latin
        Map<String, Object> r = detector.detect("h\u0391llo", null);
        assertThat((Double) r.get("lookalikeSuspicion")).isGreaterThan(0.0);
    }

    @Test
    void lookalike_cleanLatinWord_suspicionIsZero() {
        Map<String, Object> r = detector.detect("hello", null);
        assertThat(r.get("lookalikeSuspicion")).isEqualTo(0.0);
    }

    @Test
    void lookalike_pureCyrillicWord_suspicionIsZero() {
        // Pure Cyrillic — lookalike chars present but no genuine Latin chars in same word
        Map<String, Object> r = detector.detect("Привет", null);
        assertThat(r.get("lookalikeSuspicion")).isEqualTo(0.0);
    }

    // --- zeroWidthAbuse ---

    @Test
    void zeroWidth_zeroWidthSpace_detected() {
        // U+200B = ZERO WIDTH SPACE
        Map<String, Object> r = detector.detect("hello\u200Bworld", null);
        assertThat(r.get("zeroWidthAbuse")).isEqualTo(true);
    }

    @Test
    void zeroWidth_rtlOverride_detected() {
        // U+202E = RIGHT-TO-LEFT OVERRIDE
        Map<String, Object> r = detector.detect("safe\u202Eevil", null);
        assertThat(r.get("zeroWidthAbuse")).isEqualTo(true);
    }

    @Test
    void zeroWidth_normalText_notDetected() {
        Map<String, Object> r = detector.detect("normal text here", null);
        assertThat(r.get("zeroWidthAbuse")).isEqualTo(false);
    }

    // --- capsRatio ---

    @Test
    void caps_allCapsLongText_ratioAboveThreshold() {
        Map<String, Object> r = detector.detect("BUY NOW CLICK HERE", null);
        assertThat((Double) r.get("capsRatio")).isGreaterThanOrEqualTo(0.7);
    }

    @Test
    void caps_shortCaps_ratioIsZero() {
        // < 5 letters → ratio not evaluated
        Map<String, Object> r = detector.detect("OK", null);
        assertThat(r.get("capsRatio")).isEqualTo(0.0);
    }

    @Test
    void caps_germanNounSentence_ratioWellBelowThreshold() {
        // German nouns are capitalised — adds ~10–20%, well below 0.7
        Map<String, Object> r = detector.detect("Das Haus des Mannes ist groß", null);
        assertThat((Double) r.get("capsRatio")).isLessThan(0.3);
    }

    // --- emojiOnly ---

    @Test
    void emojiOnly_allEmoji_detected() {
        Map<String, Object> r = detector.detect("\uD83D\uDE02\uD83D\uDD25\uD83D\uDC40", null); // 😂🔥👀
        assertThat(r.get("emojiOnly")).isEqualTo(true);
    }

    @Test
    void emojiOnly_mixedTextAndEmoji_notDetected() {
        Map<String, Object> r = detector.detect("buy now \uD83D\uDD25", null); // "buy now 🔥"
        assertThat(r.get("emojiOnly")).isEqualTo(false);
    }

    @Test
    void emojiOnly_emptyString_notDetected() {
        Map<String, Object> r = detector.detect("", null);
        assertThat(r.get("emojiOnly")).isEqualTo(false);
    }

    // --- stickerOnly ---

    @Test
    void stickerOnly_contentTypeSticker_detected() {
        Map<String, Object> meta = Map.of("contentType", "sticker");
        Map<String, Object> r = detector.detect("", meta);
        assertThat(r.get("stickerOnly")).isEqualTo(true);
    }

    @Test
    void stickerOnly_contentTypePhoto_notDetected() {
        Map<String, Object> meta = Map.of("contentType", "photo");
        Map<String, Object> r = detector.detect("", meta);
        assertThat(r.get("stickerOnly")).isEqualTo(false);
    }

    // --- imageOnly ---

    @Test
    void imageOnly_photoNoCaption_detected() {
        Map<String, Object> meta = Map.of("contentType", "photo");
        Map<String, Object> r = detector.detect("", meta);
        assertThat(r.get("imageOnly")).isEqualTo(true);
    }

    @Test
    void imageOnly_stickerNoCaption_notDetected() {
        // stickerOnly takes precedence; imageOnly must be false for stickers
        Map<String, Object> meta = Map.of("contentType", "sticker");
        Map<String, Object> r = detector.detect("", meta);
        assertThat(r.get("imageOnly")).isEqualTo(false);
        assertThat(r.get("stickerOnly")).isEqualTo(true);
    }

    @Test
    void imageOnly_blankTextNullContentType_detected() {
        // Backward compat: old events have no contentType
        Map<String, Object> r = detector.detect("", null);
        assertThat(r.get("imageOnly")).isEqualTo(true);
    }

    @Test
    void imageOnly_photoWithCaption_notDetected() {
        Map<String, Object> meta = Map.of("contentType", "photo");
        Map<String, Object> r = detector.detect("Check this out", meta);
        assertThat(r.get("imageOnly")).isEqualTo(false);
    }

    // --- toxicityHint ---

    @Test
    void toxicity_slurPresent_hintGreaterThanZero() {
        Map<String, Object> r = detector.detect("you nigger", null);
        assertThat((Double) r.get("toxicityHint")).isGreaterThan(0.0);
    }

    @Test
    void toxicity_normalText_hintIsZero() {
        Map<String, Object> r = detector.detect("Hello how are you today", null);
        assertThat(r.get("toxicityHint")).isEqualTo(0.0);
    }

    @Test
    void toxicity_germanCompound_noFalsePositive() {
        // Whole-word \b matching must not fire on German compounds that contain
        // English slur substrings. This test documents that intent.
        Map<String, Object> r = detector.detect("Die Ausfahrt ist gesperrt", null);
        assertThat(r.get("toxicityHint")).isEqualTo(0.0);
    }

    @Test
    void toxicity_germanSlurStandalone_detected() {
        Map<String, Object> r = detector.detect("Du bist ein arschloch!", null);
        assertThat((Double) r.get("toxicityHint")).isGreaterThan(0.0);
    }

    // --- edge cases ---

    @Test
    void detect_nullText_returnsAllZeroScores() {
        Map<String, Object> r = detector.detect(null, null);
        assertThat(r.get("foreignScriptRatio")).isEqualTo(0.0);
        assertThat(r.get("lookalikeSuspicion")).isEqualTo(0.0);
        assertThat(r.get("zeroWidthAbuse")).isEqualTo(false);
        assertThat(r.get("capsRatio")).isEqualTo(0.0);
        assertThat(r.get("emojiOnly")).isEqualTo(false);
        assertThat(r.get("imageOnly")).isEqualTo(true); // blank text + null contentType = imageOnly
        assertThat(r.get("toxicityHint")).isEqualTo(0.0);
    }

    @Test
    void detect_returnsAllNineKeys() {
        Map<String, Object> r = detector.detect("hello", null);
        assertThat(r).containsKeys(
                "foreignScriptRatio", "cyrillicRatio", "lookalikeSuspicion",
                "zeroWidthAbuse", "capsRatio", "emojiOnly", "stickerOnly",
                "imageOnly", "toxicityHint");
    }
}
```

- [ ] **Step 2: Run to verify tests fail**

```bash
mvn -pl emcip-intent-classifier test -q 2>&1 | tail -20
```

Expected: compilation error — `SignalDetector` does not exist yet.

- [ ] **Step 3: Create `SignalDetector.java`**

```java
package io.emcip.intent.classifier.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Structural and script-based signal detectors. All detectors run on every message regardless
 * of lexical rule results. Scores are always returned; callers decide how to use them.
 */
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
                    0x200B, 0x200C, 0x200D, 0xFEFF, 0x00AD, 0x2060,
                    0x2061, 0x2062, 0x2063, 0x2064, // invisible operators
                    0x202A, 0x202B, 0x202C, 0x202D, 0x202E, // directional embedding/override
                    0x2066, 0x2067, 0x2068, 0x2069, // directional isolate
                    0x200E, 0x200F); // LRM / RLM

    // Content types that trigger imageOnly (non-sticker media with blank caption)
    private static final Set<String> IMAGE_CONTENT_TYPES =
            Set.of(
                    "photo", "video", "animation", "document",
                    "audio", "voice", "video_note", "other");

    // Small, high-precision toxicity list. MUST use whole-word \b matching.
    // Whole-word matching prevents false positives on German compounds
    // (e.g. "arschloch" inside a compound word won't match standalone \barschloch\b).
    private static final List<Pattern> TOXICITY_PATTERNS = buildToxicityPatterns();

    private static List<Pattern> buildToxicityPatterns() {
        String[] terms = {
            "nigger", "nigga", "faggot", "cunt", "kike", "spic", "chink",
            "wetback", "gook", "towelhead", "raghead",
            "hurensohn", "wichser", "fotze", "arschloch"
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
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
mvn -pl emcip-intent-classifier test -q 2>&1 | tail -20
```

Expected: all `SignalDetectorTest` tests pass. Existing `IntentClassificationServiceTest` tests still pass.

- [ ] **Step 5: Apply Spotless and commit**

```bash
mvn -pl emcip-intent-classifier spotless:apply
git add emcip-intent-classifier/src/
git commit -m "feat(intent-classifier): add SignalDetector with 9 structural/script detectors"
```

---

## Task 3: Wire `SignalDetector` into `IntentClassificationService`

Replace the existing `for` loop + `matchedIntent = "UNKNOWN"` fallback with the priority chain. Signal scores are always written to `parameters`.

**Files:**
- Modify: `emcip-intent-classifier/src/main/java/io/emcip/intent/classifier/service/IntentClassificationService.java`
- Modify: `emcip-intent-classifier/src/test/java/io/emcip/intent/classifier/service/IntentClassificationServiceTest.java`

- [ ] **Step 1: Write failing tests**

Add these methods to `IntentClassificationServiceTest.java`. You need `import java.util.Map;` added to imports.

Also add a helper method for creating messages with metadata:

```java
private EventSchemas.TelegramMessageEvent buildMessageWithMetadata(
        String eventId, String text, java.util.Map<String, Object> metadata) {
    return new EventSchemas.TelegramMessageEvent(
            eventId, "2026-05-13T10:00:00Z", null, null,
            1L, 100L, "user-1", "USER", text,
            1000, null, false, null, null,
            metadata, null, null, null, null);
}
```

New tests:

```java
@Test
void classify_cyrillicText_returnsScriptForeignIntent() {
    // Pure Cyrillic — foreignScriptRatio fires when no lexical rule matches
    var event = buildMessage("src-20", "Привет мир как дела");

    var result = service.classify(event, null).block();

    assertThat(result.intent()).isEqualTo("SCRIPT_FOREIGN");
    assertThat(result.confidence()).isGreaterThan(0.5);
    assertThat(result.parameters()).containsKey("foreignScriptRatio");
    assertThat((Double) result.parameters().get("foreignScriptRatio")).isGreaterThan(0.0);
}

@Test
void classify_lookalikeMixedWord_returnsLookalikeAbuseIntent() {
    // "h\u0435llo" — \u0435 is Cyrillic е mixed with Latin chars
    var event = buildMessage("src-21", "h\u0435llo");

    var result = service.classify(event, null).block();

    assertThat(result.intent()).isEqualTo("LOOKALIKE_ABUSE");
    assertThat((Double) result.parameters().get("lookalikeSuspicion")).isGreaterThan(0.0);
}

@Test
void classify_zeroWidthChar_returnsFormatAbuseIntent() {
    // "safe\u202Eevil" — U+202E is RIGHT-TO-LEFT OVERRIDE
    var event = buildMessage("src-22", "pay\u202Enow");

    var result = service.classify(event, null).block();

    assertThat(result.intent()).isEqualTo("FORMAT_ABUSE");
    assertThat(result.parameters().get("zeroWidthAbuse")).isEqualTo(true);
}

@Test
void classify_allCapsNoLexicalMatch_returnsCapsHeavyIntent() {
    var event = buildMessage("src-23", "THIS IS IMPORTANT READ IT NOW");

    var result = service.classify(event, null).block();

    assertThat(result.intent()).isEqualTo("CAPS_HEAVY");
    assertThat((Double) result.parameters().get("capsRatio")).isGreaterThanOrEqualTo(0.7);
}

@Test
void classify_emojiOnly_returnsEmojiOnlyIntent() {
    var event = buildMessage("src-24", "\uD83D\uDE02\uD83D\uDD25\uD83D\uDC40"); // 😂🔥👀

    var result = service.classify(event, null).block();

    assertThat(result.intent()).isEqualTo("FORMAT_EMOJI_ONLY");
    assertThat(result.parameters().get("emojiOnly")).isEqualTo(true);
}

@Test
void classify_stickerMessage_returnsFormatStickerOnly() {
    var event = buildMessageWithMetadata("src-25", "", java.util.Map.of("contentType", "sticker"));

    var result = service.classify(event, null).block();

    assertThat(result.intent()).isEqualTo("FORMAT_STICKER_ONLY");
    assertThat(result.parameters().get("stickerOnly")).isEqualTo(true);
}

@Test
void classify_photoNoCaption_returnsImageOnly() {
    var event = buildMessageWithMetadata("src-26", "", java.util.Map.of("contentType", "photo"));

    var result = service.classify(event, null).block();

    assertThat(result.intent()).isEqualTo("FORMAT_IMAGE_ONLY");
    assertThat(result.parameters().get("imageOnly")).isEqualTo(true);
}

@Test
void classify_spamWithCyrillic_spamWinsAndSignalScoresInParams() {
    // Lexical SPAM rule wins; foreignScriptRatio is still present in parameters
    var event = buildMessage("src-27", "Купить click here buy now");

    var result = service.classify(event, null).block();

    assertThat(result.intent()).isEqualTo("SPAM");
    assertThat(result.parameters()).containsKey("foreignScriptRatio");
    assertThat((Double) result.parameters().get("foreignScriptRatio")).isGreaterThan(0.0);
}

@Test
void classify_germanSentence_returnsUnknownNoSignalFired() {
    var event = buildMessage("src-28", "Das Haus des Mannes ist groß");

    var result = service.classify(event, null).block();

    assertThat(result.intent()).isEqualTo("UNKNOWN");
    assertThat(result.parameters().get("foreignScriptRatio")).isEqualTo(0.0);
    assertThat((Double) result.parameters().get("capsRatio")).isLessThan(0.3);
}

@Test
void classify_signalScoresAlwaysPresentInParameters() {
    var event = buildMessage("src-29", "hello world");

    var result = service.classify(event, null).block();

    // All signal keys must be present regardless of intent
    assertThat(result.parameters()).containsKeys(
            "foreignScriptRatio", "cyrillicRatio", "lookalikeSuspicion",
            "zeroWidthAbuse", "capsRatio", "emojiOnly", "stickerOnly",
            "imageOnly", "toxicityHint");
}
```

- [ ] **Step 2: Run to verify new tests fail**

```bash
mvn -pl emcip-intent-classifier test -q 2>&1 | grep -E "FAIL|ERROR|Tests run" | tail -20
```

Expected: new tests fail (signal intents not yet assigned; signal scores not yet in params).

- [ ] **Step 3: Modify `IntentClassificationService.classify()`**

Replace the entire `classify()` method body (the lambda passed to `Mono.fromCallable`) as follows. The `signalDetector` field is also added.

Add field after the `rules` field declaration (line 34):
```java
private final SignalDetector signalDetector = new SignalDetector();
```

Replace the lambda body inside `classify()` (currently lines 73–127) with:

```java
return Mono.fromCallable(
        () -> {
            String text = message.text();
            String matchedIntent = null;
            double highestConfidence = 0.0;
            List<String> matchedRules = new ArrayList<>();

            // a. Apply lexical rules
            for (IntentRule rule : rules) {
                if (rule.pattern.matcher(text).find()) {
                    matchedRules.add(rule.name);
                    if (rule.confidence > highestConfidence) {
                        highestConfidence = rule.confidence;
                        matchedIntent = rule.name;
                    }
                }
            }

            // Run all signal detectors (always — regardless of lexical match)
            Map<String, Object> signals = signalDetector.detect(text, message.metadata());

            // b–j. Priority chain (only when no lexical rule fired)
            if (matchedIntent == null) {
                if (Boolean.TRUE.equals(signals.get("stickerOnly"))) {
                    matchedIntent = "FORMAT_STICKER_ONLY";
                    highestConfidence = 1.0;
                    matchedRules.add("FORMAT_STICKER_ONLY");
                } else if (Boolean.TRUE.equals(signals.get("imageOnly"))) {
                    matchedIntent = "FORMAT_IMAGE_ONLY";
                    highestConfidence = 1.0;
                    matchedRules.add("FORMAT_IMAGE_ONLY");
                } else if (Boolean.TRUE.equals(signals.get("emojiOnly"))) {
                    matchedIntent = "FORMAT_EMOJI_ONLY";
                    highestConfidence = 1.0;
                    matchedRules.add("FORMAT_EMOJI_ONLY");
                } else if ((Double) signals.get("lookalikeSuspicion") > 0.0) {
                    double score = (Double) signals.get("lookalikeSuspicion");
                    matchedIntent = "LOOKALIKE_ABUSE";
                    highestConfidence = score;
                    matchedRules.add("LOOKALIKE_ABUSE");
                } else if (Boolean.TRUE.equals(signals.get("zeroWidthAbuse"))) {
                    matchedIntent = "FORMAT_ABUSE";
                    highestConfidence = 1.0;
                    matchedRules.add("FORMAT_ABUSE");
                } else if ((Double) signals.get("foreignScriptRatio") >= 0.6) {
                    double score = (Double) signals.get("foreignScriptRatio");
                    matchedIntent = "SCRIPT_FOREIGN";
                    highestConfidence = score;
                    matchedRules.add("SCRIPT_FOREIGN");
                } else if ((Double) signals.get("capsRatio") >= 0.7) {
                    double score = (Double) signals.get("capsRatio");
                    matchedIntent = "CAPS_HEAVY";
                    highestConfidence = score;
                    matchedRules.add("CAPS_HEAVY");
                } else if ((Double) signals.get("toxicityHint") > 0.0) {
                    double score = (Double) signals.get("toxicityHint");
                    matchedIntent = "TOXICITY_HINT";
                    highestConfidence = score;
                    matchedRules.add("TOXICITY_HINT");
                } else {
                    matchedIntent = "UNKNOWN";
                }
            }

            // Build parameters — signal scores always included
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("textLength", text.length());
            params.put("chatId", message.chatId());
            params.put("senderId", message.senderId() != null ? message.senderId() : "");
            params.put("messageText", text);
            if (message.telegramMessageId() != null) {
                params.put("telegramMessageId", message.telegramMessageId());
            }
            params.putAll(signals);

            var classification =
                    new EventSchemas.IntentClassifiedEvent(
                            UUID.randomUUID().toString(),
                            Instant.now().toString(),
                            EventSchemas.INTENT_CLASSIFIED_V1,
                            "IntentClassified",
                            message.eventId(),
                            matchedIntent,
                            highestConfidence,
                            params,
                            matchedRules);

            String json = objectMapper.writeValueAsString(classification);
            ProducerRecord<String, String> producerRecord =
                    new ProducerRecord<>(TOPIC_OUTPUT, null, message.eventId(), json);
            if (tenantId != null) {
                producerRecord
                        .headers()
                        .add(
                                "tenant_id",
                                tenantId.getBytes(StandardCharsets.UTF_8));
            }
            kafkaTemplate.send(producerRecord);

            log.debug(
                    "Published classification for message {}: {}",
                    message.eventId(),
                    matchedIntent);
            return classification;
        });
```

- [ ] **Step 4: Run all intent-classifier tests**

```bash
mvn -pl emcip-intent-classifier test -q 2>&1 | tail -20
```

Expected: all tests pass including the 10 new ones and all original 8. Key regressions to verify:
- `classify_noMatch_returnsUnknown` — still UNKNOWN, `matchedRules.isEmpty()` ✓
- `classify_spam_returnsSpamIntent` — still SPAM (lexical rule wins) ✓
- `classify_populatesSourceEventIdAndMetadata` — still has textLength, chatId, messageText, telegramMessageId ✓

- [ ] **Step 5: Apply Spotless and commit**

```bash
mvn -pl emcip-intent-classifier spotless:apply
git add emcip-intent-classifier/src/
git commit -m "feat(intent-classifier): wire SignalDetector into classify() with priority chain"
```

---

## Task 4: Policy-engine — signal param forwarding

`PolicyEvaluationService.persistDecision()` currently only copies 4 specific keys from parameters. Signal scores are silently dropped. Fix both `persistDecision()` (→ `PolicyDecision.metadata`) and the `PolicyDecisionEvent` context map.

**Files:**
- Modify: `emcip-policy-engine/src/main/java/io/emcip/policy/engine/service/PolicyEvaluationService.java`
- Modify: `emcip-policy-engine/src/test/java/io/emcip/policy/engine/service/PolicyEvaluationServiceTest.java`

- [ ] **Step 1: Write failing test**

Add to `PolicyEvaluationServiceTest.java`. Add import: `import static org.mockito.Mockito.atLeastOnce;` and `import org.mockito.ArgumentCaptor;`.

```java
@Test
@DisplayName("Should forward signal params to PolicyDecision metadata and PolicyDecisionEvent context")
@SuppressWarnings("unchecked")
void shouldForwardSignalParamsToDecisionMetadataAndEventContext() {
    // Given
    when(ruleConfigRepository.findEffectiveRulesAt(any(Instant.class)))
            .thenReturn(Collections.emptyList());
    when(decisionRepository.save(any()))
            .thenAnswer(
                    inv -> {
                        PolicyDecision d = inv.getArgument(0);
                        d.setId("test-signal-id");
                        return d;
                    });

    Map<String, Object> params = new java.util.LinkedHashMap<>();
    params.put("messageText", "Привет мир");
    params.put("chatId", 100L);
    params.put("senderId", "user-1");
    params.put("foreignScriptRatio", 0.8);
    params.put("cyrillicRatio", 0.8);
    params.put("lookalikeSuspicion", 0.0);
    params.put("zeroWidthAbuse", false);
    params.put("capsRatio", 0.0);
    params.put("emojiOnly", false);
    params.put("stickerOnly", false);
    params.put("imageOnly", false);
    params.put("toxicityHint", 0.0);

    var classification =
            new EventSchemas.IntentClassifiedEvent(
                    "evt-sig-1",
                    Instant.now().toString(),
                    EventSchemas.INTENT_CLASSIFIED_V1,
                    "IntentClassified",
                    "src-sig-1",
                    "SCRIPT_FOREIGN",
                    0.8,
                    params,
                    List.of("SCRIPT_FOREIGN"));

    // When
    PolicyDecision result = policyService.evaluate(classification, null);

    // Then: PolicyDecision.metadata contains signal scores
    assertThat(result.getMetadata()).containsKey("foreignScriptRatio");
    assertThat(result.getMetadata().get("foreignScriptRatio")).isEqualTo(0.8);
    assertThat(result.getMetadata()).containsKey("lookalikeSuspicion");

    // And: PolicyDecisionEvent context serialised to Kafka contains signal scores
    ArgumentCaptor<org.apache.kafka.clients.producer.ProducerRecord<String, String>> captor =
            ArgumentCaptor.forClass(
                    org.apache.kafka.clients.producer.ProducerRecord.class);
    verify(kafkaTemplate, atLeastOnce()).send(captor.capture());
    assertThat(captor.getValue().value()).contains("foreignScriptRatio");

    // And: original four fields still forwarded
    assertThat(result.getMetadata()).containsKey("messageText");
    assertThat(result.getMetadata()).containsKey("chatId");
}
```

- [ ] **Step 2: Run to verify test fails**

```bash
mvn -pl emcip-policy-engine test -q 2>&1 | grep -E "shouldForwardSignal|FAIL|ERROR" | head -10
```

Expected: the new test fails (`foreignScriptRatio` not in metadata).

- [ ] **Step 3: Implement changes in `PolicyEvaluationService.java`**

**3a. Add constant** after the `TOPIC_OUTPUT` constant (line 29):

```java
private static final Set<String> SIGNAL_PARAM_KEYS =
        Set.of(
                "foreignScriptRatio",
                "cyrillicRatio",
                "lookalikeSuspicion",
                "zeroWidthAbuse",
                "capsRatio",
                "emojiOnly",
                "stickerOnly",
                "imageOnly",
                "toxicityHint");
```

**3b. Add signal forwarding in `persistDecision()`** — add after the existing four `if (params.containsKey(...))` blocks and before `policyDecision.setMetadata(meta)` (around line 244):

```java
for (String key : SIGNAL_PARAM_KEYS) {
    if (params.containsKey(key)) meta.put(key, params.get(key));
}
```

**3c. Fix `PolicyDecisionEvent` context in `evaluate()`** — replace the immutable `Map.of(...)` block (lines 169–172) with a call to a new helper:

Replace:
```java
Map.of(
    "originalIntent", classification.intent(),
    "confidence", classification.confidence(),
    "matchedRules", classification.matchedRules()),
```

With:
```java
buildDecisionContext(classification),
```

Add the helper method to the class (after `matchesContextConditions`):

```java
private Map<String, Object> buildDecisionContext(
        EventSchemas.IntentClassifiedEvent classification) {
    Map<String, Object> ctx = new java.util.LinkedHashMap<>();
    ctx.put("originalIntent", classification.intent());
    ctx.put("confidence", classification.confidence());
    ctx.put("matchedRules", classification.matchedRules());
    Map<String, Object> params =
            classification.parameters() != null ? classification.parameters() : Map.of();
    for (String key : SIGNAL_PARAM_KEYS) {
        if (params.containsKey(key)) ctx.put(key, params.get(key));
    }
    return ctx;
}
```

- [ ] **Step 4: Run all policy-engine tests**

```bash
mvn -pl emcip-policy-engine test -q 2>&1 | tail -20
```

Expected: all tests pass including the new signal-forwarding test.

- [ ] **Step 5: Apply Spotless and commit**

```bash
mvn -pl emcip-policy-engine spotless:apply
git add emcip-policy-engine/src/
git commit -m "feat(policy-engine): forward signal detector scores to PolicyDecision metadata and event context"
```

---

## Task 5: Full test run + PR

- [ ] **Step 1: Run all three modules**

```bash
mvn -pl emcip-tdlib-adapter,emcip-intent-classifier,emcip-policy-engine test -q 2>&1 | tail -30
```

Expected: all tests pass across all three modules. Fix any failures before continuing.

- [ ] **Step 2: Update `BACKLOG.md`**

In `docs/superpowers/BACKLOG.md`, change item #36 from `⏳` state to `✅`:

```
| 36 | **Signal detectors in intent-classifier** | M | ✅ PR #NNN — 2026-06-08. ... |
```

- [ ] **Step 3: Create PR**

```bash
git push -u origin feat/signal-detectors
gh pr create \
  --title "feat(classifier): signal detectors — 9 structural/script abuse patterns" \
  --body "$(cat <<'EOF'
## Summary
- **TDLib adapter**: `extractMetadata()` now sets `contentType` (`text|sticker|photo|video|...`) on every message, enabling sticker/image detection downstream
- **Intent classifier**: new `SignalDetector` class with 9 detectors: `foreignScriptRatio`, `cyrillicRatio`, `lookalikeSuspicion` (Cyrillic+Greek lookalike pairs), `zeroWidthAbuse` (invisible/direction-override chars), `capsRatio`, `emojiOnly`, `stickerOnly`, `imageOnly`, `toxicityHint` (whole-word, `UNICODE_CHARACTER_CLASS`)
- **Policy engine**: `SIGNAL_PARAM_KEYS` constant; signal scores forwarded into `PolicyDecision.metadata` and `PolicyDecisionEvent.context` so flags UI and llm-orchestrator receive them

## Test plan
- [ ] New `SignalDetectorTest` covers all 9 detectors including German false-positive guards
- [ ] New `IntentClassificationServiceTest` tests verify all 8 new intents are assigned with correct priority
- [ ] Existing classifier tests unchanged (lexical rules still win)
- [ ] New `PolicyEvaluationServiceTest` test verifies signal keys present in metadata and event JSON
- [ ] All three modules green: `mvn -pl emcip-tdlib-adapter,emcip-intent-classifier,emcip-policy-engine test`

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

---

## Self-Review

**Spec coverage check:**
- `foreignScriptRatio` + `cyrillicRatio` — Task 2 SignalDetector ✓
- `lookalikeSuspicion` with full Cyrillic+Greek tables — Task 2 ✓
- `zeroWidthAbuse` with full codepoint set — Task 2 ✓
- `capsRatio` (threshold 0.7, min 5 letters) — Task 2 ✓
- `emojiOnly` — Task 2 ✓
- `stickerOnly` (requires TDLib contentType) — Task 1 + Task 2 ✓
- `imageOnly` (non-sticker media or blank+no contentType) — Task 2 ✓
- `toxicityHint` (whole-word `\b`, `UNICODE_CHARACTER_CLASS`) — Task 2 ✓
- `contentType` switch in TDLib adapter — Task 1 ✓
- Priority chain in `IntentClassificationService` — Task 3 ✓
- Signal scores always in `parameters` regardless of intent — Task 3 ✓
- `SIGNAL_PARAM_KEYS` forwarding in `PolicyEvaluationService` — Task 4 ✓
- German false-positive tests — Task 2 + Task 3 ✓

**Notes for implementer:**
- The Cyrillic lookalike codepoints in `CYRILLIC_LOOKALIKES` are intentional: lookalike chars are NOT counted in `foreignScriptRatio` (they are handled by `lookalikeSuspicion` instead)
- `imageOnly` fires for blank text + null contentType — this is backward compatibility for pre-Task-1 events
- `zeroWidthAbuse` includes U+200D (ZERO WIDTH JOINER), which is also used in emoji family sequences. When `emojiOnly=true`, the emoji intent takes priority (d before f in the chain). Both flags are still written to params.
- Do NOT lower the `capsRatio` threshold below 0.6 — German noun capitalisation sits at ~10–20%, not 60–70%
- The toxicity list is intentionally small (~15 terms) — it's a high-precision pre-AI signal, not a comprehensive filter
