package io.emcip.knowledge.engine.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SentenceAwareChunkerTest {

    private SentenceAwareChunker chunker;

    @BeforeEach
    void setUp() {
        chunker =
                new SentenceAwareChunker(
                        300, // targetTokens
                        2, // overlapSentences
                        20, // minSentenceLength
                        500, // maxChunks
                        "Dr.,Prof.,Dipl.,Ing.,Str.,z.B.,bzw.,usw.,etc.,Nr.,Abs.,Art.,ggf.,ca.,d.h.,u.a.,o.ä.,i.d.R.,e.V.,GmbH.,AG.");
    }

    @Test
    void chunk_returnsEmptyListForNullInput() {
        assertThat(chunker.chunk(null)).isEmpty();
    }

    @Test
    void chunk_returnsEmptyListForBlankInput() {
        assertThat(chunker.chunk("   ")).isEmpty();
    }

    @Test
    void chunk_returnsSingleChunkForShortText() {
        String text = "This is a short sentence. It has two sentences.";
        List<String> chunks = chunker.chunk(text);
        assertThat(chunks).hasSize(1);
        assertThat(chunks.getFirst()).isEqualTo(text);
    }

    @Test
    void chunk_splitsLongTextIntoMultipleChunks() {
        // Build text with many sentences (22 words each, 15 sentences = ~429 tokens, > 300 target)
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 15; i++) {
            sb.append("This is sentence number ")
                    .append(i)
                    .append(" and it contains enough words to contribute meaningfully ")
                    .append("to the overall token count of this test document. ");
        }
        List<String> chunks = chunker.chunk(sb.toString());
        assertThat(chunks).hasSizeGreaterThan(1);
    }

    @Test
    void chunk_preservesGermanAbbreviations() {
        String text =
                "Dr. Müller arbeitet in der Hauptstr. 5 in Berlin. "
                        + "Prof. Schmidt hat z.B. eine neue Studie veröffentlicht. "
                        + "Die GmbH. wurde bzw. wird weiterhin unterstützt.";
        List<String> chunks = chunker.chunk(text);
        // All text fits in one chunk — abbreviations should NOT cause extra splits
        assertThat(chunks).hasSize(1);
        // Verify no zero-width spaces leaked into output
        for (String chunk : chunks) {
            assertThat(chunk).doesNotContain("\u200B");
        }
    }

    @Test
    void chunk_handlesOverlapBetweenChunks() {
        // Create text with distinct sentences that will span multiple chunks
        // Target is 300 tokens. Each sentence ~25 words = ~33 tokens. 12 sentences = ~396 tokens.
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 12; i++) {
            sb.append("Sentence ").append(i).append(" contains about twenty five words ");
            sb.append("which means this particular sentence contributes roughly ");
            sb.append("thirty three tokens to the overall count. ");
        }
        List<String> chunks = chunker.chunk(sb.toString());
        assertThat(chunks).hasSizeGreaterThan(1);

        // The last 2 sentences of chunk 0 should appear at the start of chunk 1 (overlap=2)
        if (chunks.size() >= 2) {
            String chunk0 = chunks.get(0);
            String chunk1 = chunks.get(1);
            // Extract last sentence from chunk 0 — it should appear in chunk 1
            String[] sentences0 = chunk0.split("(?<=\\.) ");
            String lastSentenceOfChunk0 = sentences0[sentences0.length - 1].trim();
            assertThat(chunk1).contains(lastSentenceOfChunk0);
        }
    }

    @Test
    void chunk_mergesShortSentencesWithPrevious() {
        // "Yes." is < 20 chars — should merge with previous sentence
        String text =
                "This is a normal length sentence that passes the minimum. Yes. Another full"
                        + " sentence here.";
        List<String> chunks = chunker.chunk(text);
        assertThat(chunks).hasSize(1);
        // "Yes." should be merged, not a standalone sentence
        assertThat(chunks.getFirst()).contains("Yes.");
    }

    @Test
    void chunk_respectsMaxChunksLimit() {
        SentenceAwareChunker limitedChunker =
                new SentenceAwareChunker(10, 0, 5, 3, ""); // very small target, max 3 chunks
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            sb.append("This is a test sentence number ").append(i).append(". ");
        }
        List<String> chunks = limitedChunker.chunk(sb.toString());
        assertThat(chunks).hasSizeLessThanOrEqualTo(3);
    }

    @Test
    void chunk_caseInsensitiveAbbreviationProtection() {
        // "dr." lowercase should also be protected
        String text = "Der dr. med. Fischer war heute im Büro. Er hat viele Patienten behandelt.";
        List<String> chunks = chunker.chunk(text);
        assertThat(chunks).hasSize(1);
        assertThat(chunks.getFirst()).doesNotContain("\u200B");
    }
}
