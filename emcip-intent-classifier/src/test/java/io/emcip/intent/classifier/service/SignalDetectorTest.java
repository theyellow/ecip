package io.emcip.intent.classifier.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SignalDetectorTest {

    private static final List<String> DEFAULT_WORDS =
            List.of(
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
                    "arschloch");

    private SignalDetector detector;

    @BeforeEach
    void setUp() {
        detector = new SignalDetector();
    }

    // --- foreignScriptRatio + cyrillicRatio ---

    @Test
    void foreignScript_pureCyrillic_ratioPproachesOne() {
        Map<String, Object> r =
                detector.detect(
                        "Привет мир", null, SignalDetector.buildToxicityPatterns(DEFAULT_WORDS));
        assertThat((Double) r.get("foreignScriptRatio")).isGreaterThanOrEqualTo(0.6);
        assertThat((Double) r.get("cyrillicRatio")).isGreaterThan(0.0);
    }

    @Test
    void foreignScript_pureGreek_ratioAboveThreshold() {
        // "Γεια σου" = "Hello you" in Greek
        Map<String, Object> r =
                detector.detect(
                        "Γεια σου φίλε", null, SignalDetector.buildToxicityPatterns(DEFAULT_WORDS));
        assertThat((Double) r.get("foreignScriptRatio")).isGreaterThan(0.0);
    }

    @Test
    void foreignScript_latinText_ratioIsZero() {
        Map<String, Object> r =
                detector.detect(
                        "Hello world", null, SignalDetector.buildToxicityPatterns(DEFAULT_WORDS));
        assertThat(r.get("foreignScriptRatio")).isEqualTo(0.0);
        assertThat(r.get("cyrillicRatio")).isEqualTo(0.0);
    }

    @Test
    void foreignScript_germanUmlauts_ratioIsZero() {
        // ä ö ü Ä Ö Ü ß are Latin Extended (U+00C0–U+024F) — NOT foreign script
        Map<String, Object> r =
                detector.detect(
                        "Über die Größe der Öffnung",
                        null,
                        SignalDetector.buildToxicityPatterns(DEFAULT_WORDS));
        assertThat(r.get("foreignScriptRatio")).isEqualTo(0.0);
    }

    // --- lookalikeSuspicion ---

    @Test
    void lookalike_cyrillicMixedWord_suspicionGreaterThanZero() {
        // "h\u0435llo" — \u0435 is Cyrillic е mixed with Latin chars
        Map<String, Object> r =
                detector.detect(
                        "h\u0435llo", null, SignalDetector.buildToxicityPatterns(DEFAULT_WORDS));
        assertThat((Integer) r.get("lookalikeSuspicion")).isGreaterThan(0);
    }

    @Test
    void lookalike_greekMixedWord_suspicionGreaterThanZero() {
        // "h\u0391llo" — \u0391 is Greek Α mixed with Latin chars
        Map<String, Object> r =
                detector.detect(
                        "h\u0391llo", null, SignalDetector.buildToxicityPatterns(DEFAULT_WORDS));
        assertThat((Integer) r.get("lookalikeSuspicion")).isGreaterThan(0);
    }

    @Test
    void lookalike_cleanLatinWord_suspicionIsZero() {
        Map<String, Object> r =
                detector.detect("hello", null, SignalDetector.buildToxicityPatterns(DEFAULT_WORDS));
        assertThat(r.get("lookalikeSuspicion")).isEqualTo(0);
    }

    @Test
    void lookalike_pureCyrillicWord_suspicionIsZero() {
        // Pure Cyrillic — lookalike chars present but no genuine Latin chars in same word
        Map<String, Object> r =
                detector.detect(
                        "Привет", null, SignalDetector.buildToxicityPatterns(DEFAULT_WORDS));
        assertThat(r.get("lookalikeSuspicion")).isEqualTo(0);
    }

    // --- zeroWidthAbuse ---

    @Test
    void zeroWidth_zeroWidthSpace_detected() {
        // U+200B = ZERO WIDTH SPACE
        Map<String, Object> r =
                detector.detect(
                        "hello\u200Bworld",
                        null,
                        SignalDetector.buildToxicityPatterns(DEFAULT_WORDS));
        assertThat((Integer) r.get("zeroWidthAbuse")).isGreaterThanOrEqualTo(1);
    }

    @Test
    void zeroWidth_rtlOverride_detected() {
        // U+202E = RIGHT-TO-LEFT OVERRIDE
        Map<String, Object> r =
                detector.detect(
                        "safe\u202Eevil",
                        null,
                        SignalDetector.buildToxicityPatterns(DEFAULT_WORDS));
        assertThat((Integer) r.get("zeroWidthAbuse")).isGreaterThanOrEqualTo(1);
    }

    @Test
    void zeroWidth_normalText_notDetected() {
        Map<String, Object> r =
                detector.detect(
                        "normal text here",
                        null,
                        SignalDetector.buildToxicityPatterns(DEFAULT_WORDS));
        assertThat(r.get("zeroWidthAbuse")).isEqualTo(0);
    }

    // --- capsRatio ---

    @Test
    void caps_allCapsLongText_ratioAboveThreshold() {
        Map<String, Object> r =
                detector.detect(
                        "BUY NOW CLICK HERE",
                        null,
                        SignalDetector.buildToxicityPatterns(DEFAULT_WORDS));
        assertThat((Double) r.get("capsRatio")).isGreaterThanOrEqualTo(0.7);
    }

    @Test
    void caps_shortCaps_ratioIsZero() {
        // < 5 letters → ratio not evaluated
        Map<String, Object> r =
                detector.detect("OK", null, SignalDetector.buildToxicityPatterns(DEFAULT_WORDS));
        assertThat(r.get("capsRatio")).isEqualTo(0.0);
    }

    @Test
    void caps_germanNounSentence_ratioWellBelowThreshold() {
        // German nouns are capitalised — adds ~10–20%, well below 0.7
        Map<String, Object> r =
                detector.detect(
                        "Das Haus des Mannes ist groß",
                        null,
                        SignalDetector.buildToxicityPatterns(DEFAULT_WORDS));
        assertThat((Double) r.get("capsRatio")).isLessThan(0.3);
    }

    // --- emojiOnly ---

    @Test
    void emojiOnly_allEmoji_detected() {
        Map<String, Object> r =
                detector.detect(
                        "\uD83D\uDE02\uD83D\uDD25\uD83D\uDC40",
                        null,
                        SignalDetector.buildToxicityPatterns(DEFAULT_WORDS)); // 😂🔥👀
        assertThat(r.get("emojiOnly")).isEqualTo(true);
    }

    @Test
    void emojiOnly_mixedTextAndEmoji_notDetected() {
        Map<String, Object> r =
                detector.detect(
                        "buy now \uD83D\uDD25",
                        null,
                        SignalDetector.buildToxicityPatterns(DEFAULT_WORDS)); // "buy now 🔥"
        assertThat(r.get("emojiOnly")).isEqualTo(false);
    }

    @Test
    void emojiOnly_emptyString_notDetected() {
        Map<String, Object> r =
                detector.detect("", null, SignalDetector.buildToxicityPatterns(DEFAULT_WORDS));
        assertThat(r.get("emojiOnly")).isEqualTo(false);
    }

    // --- stickerOnly ---

    @Test
    void stickerOnly_contentTypeSticker_detected() {
        Map<String, Object> meta = Map.of("contentType", "sticker");
        Map<String, Object> r =
                detector.detect("", meta, SignalDetector.buildToxicityPatterns(DEFAULT_WORDS));
        assertThat(r.get("stickerOnly")).isEqualTo(true);
    }

    @Test
    void stickerOnly_contentTypePhoto_notDetected() {
        Map<String, Object> meta = Map.of("contentType", "photo");
        Map<String, Object> r =
                detector.detect("", meta, SignalDetector.buildToxicityPatterns(DEFAULT_WORDS));
        assertThat(r.get("stickerOnly")).isEqualTo(false);
    }

    // --- imageOnly ---

    @Test
    void imageOnly_photoNoCaption_detected() {
        Map<String, Object> meta = Map.of("contentType", "photo");
        Map<String, Object> r =
                detector.detect("", meta, SignalDetector.buildToxicityPatterns(DEFAULT_WORDS));
        assertThat(r.get("imageOnly")).isEqualTo(true);
    }

    @Test
    void imageOnly_stickerNoCaption_notDetected() {
        // stickerOnly takes precedence; imageOnly must be false for stickers
        Map<String, Object> meta = Map.of("contentType", "sticker");
        Map<String, Object> r =
                detector.detect("", meta, SignalDetector.buildToxicityPatterns(DEFAULT_WORDS));
        assertThat(r.get("imageOnly")).isEqualTo(false);
        assertThat(r.get("stickerOnly")).isEqualTo(true);
    }

    @Test
    void imageOnly_blankTextNullContentType_detected() {
        // Backward compat: old events have no contentType
        Map<String, Object> r =
                detector.detect("", null, SignalDetector.buildToxicityPatterns(DEFAULT_WORDS));
        assertThat(r.get("imageOnly")).isEqualTo(true);
    }

    @Test
    void imageOnly_photoWithCaption_notDetected() {
        Map<String, Object> meta = Map.of("contentType", "photo");
        Map<String, Object> r =
                detector.detect(
                        "Check this out",
                        meta,
                        SignalDetector.buildToxicityPatterns(DEFAULT_WORDS));
        assertThat(r.get("imageOnly")).isEqualTo(false);
    }

    // --- toxicityHint ---

    @Test
    void toxicity_slurPresent_hintGreaterThanZero() {
        Map<String, Object> r =
                detector.detect(
                        "you nigger", null, SignalDetector.buildToxicityPatterns(DEFAULT_WORDS));
        assertThat((Double) r.get("toxicityHint")).isGreaterThan(0.0);
    }

    @Test
    void toxicity_normalText_hintIsZero() {
        Map<String, Object> r =
                detector.detect(
                        "Hello how are you today",
                        null,
                        SignalDetector.buildToxicityPatterns(DEFAULT_WORDS));
        assertThat(r.get("toxicityHint")).isEqualTo(0.0);
    }

    @Test
    void toxicity_germanCompound_noFalsePositive() {
        // Whole-word \b matching must not fire on German compounds
        Map<String, Object> r =
                detector.detect(
                        "Die Ausfahrt ist gesperrt",
                        null,
                        SignalDetector.buildToxicityPatterns(DEFAULT_WORDS));
        assertThat(r.get("toxicityHint")).isEqualTo(0.0);
    }

    @Test
    void toxicity_germanSlurStandalone_detected() {
        Map<String, Object> r =
                detector.detect(
                        "Du bist ein arschloch!",
                        null,
                        SignalDetector.buildToxicityPatterns(DEFAULT_WORDS));
        assertThat((Double) r.get("toxicityHint")).isGreaterThan(0.0);
    }

    // --- edge cases ---

    @Test
    void detect_nullText_returnsAllZeroScores() {
        Map<String, Object> r =
                detector.detect(null, null, SignalDetector.buildToxicityPatterns(DEFAULT_WORDS));
        assertThat(r.get("foreignScriptRatio")).isEqualTo(0.0);
        assertThat(r.get("lookalikeSuspicion")).isEqualTo(0);
        assertThat(r.get("zeroWidthAbuse")).isEqualTo(0);
        assertThat(r.get("capsRatio")).isEqualTo(0.0);
        assertThat(r.get("emojiOnly")).isEqualTo(false);
        assertThat(r.get("imageOnly")).isEqualTo(true); // blank text + null contentType = imageOnly
        assertThat(r.get("toxicityHint")).isEqualTo(0.0);
    }

    @Test
    void detect_returnsAllNineKeys() {
        Map<String, Object> r =
                detector.detect("hello", null, SignalDetector.buildToxicityPatterns(DEFAULT_WORDS));
        assertThat(r)
                .containsKeys(
                        "foreignScriptRatio",
                        "cyrillicRatio",
                        "lookalikeSuspicion",
                        "zeroWidthAbuse",
                        "capsRatio",
                        "emojiOnly",
                        "stickerOnly",
                        "imageOnly",
                        "toxicityHint");
    }
}
