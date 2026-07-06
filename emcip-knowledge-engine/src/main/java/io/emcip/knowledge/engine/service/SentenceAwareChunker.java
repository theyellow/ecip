package io.emcip.knowledge.engine.service;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class SentenceAwareChunker {

    private static final double TOKEN_MULTIPLIER = 1.3;
    private static final String PLACEHOLDER = "\u200B";

    private final int targetTokens;
    private final int overlapSentences;
    private final int minSentenceLength;
    private final int maxChunks;
    private final List<Pattern> abbreviationPatterns;

    public SentenceAwareChunker(
            @Value("${knowledge.chunking.target-tokens:300}") int targetTokens,
            @Value("${knowledge.chunking.overlap-sentences:2}") int overlapSentences,
            @Value("${knowledge.chunking.min-sentence-length:20}") int minSentenceLength,
            @Value("${knowledge.chunking.max-chunks:500}") int maxChunks,
            @Value(
                            "${knowledge.chunking.abbreviations:Dr.,Prof.,Dipl.,Ing.,Str.,z.B.,bzw.,usw.,etc.,Nr.,Abs.,Art.,ggf.,ca.,d.h.,u.a.,o.ä.,i.d.R.,e.V.,GmbH.,AG.}")
                    String abbreviations) {
        this.targetTokens = targetTokens;
        this.overlapSentences = overlapSentences;
        this.minSentenceLength = minSentenceLength;
        this.maxChunks = maxChunks;
        this.abbreviationPatterns = buildAbbreviationPatterns(abbreviations);
    }

    public List<String> chunk(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        // Step 1: Protect abbreviations
        String protectedText = protectAbbreviations(text);

        // Step 2: Split into sentences
        List<String> sentences = splitSentences(protectedText);

        // Step 3: Restore placeholders in sentences
        sentences = sentences.stream().map(s -> s.replace(PLACEHOLDER, ".")).toList();

        // Step 4: Merge short sentences with previous
        sentences = mergeShortSentences(sentences);

        // Step 5: Build chunks with overlap
        return buildChunks(sentences);
    }

    private String protectAbbreviations(String text) {
        String result = text;
        for (Pattern pattern : abbreviationPatterns) {
            result = pattern.matcher(result).replaceAll(m -> m.group().replace(".", PLACEHOLDER));
        }
        return result;
    }

    private List<String> splitSentences(String text) {
        List<String> sentences = new ArrayList<>();
        BreakIterator iterator = BreakIterator.getSentenceInstance(Locale.GERMAN);
        iterator.setText(text);

        int start = iterator.first();
        for (int end = iterator.next();
                end != BreakIterator.DONE;
                start = end, end = iterator.next()) {
            String sentence = text.substring(start, end).trim();
            if (!sentence.isEmpty()) {
                sentences.add(sentence);
            }
        }
        return sentences;
    }

    private List<String> mergeShortSentences(List<String> sentences) {
        if (sentences.isEmpty()) return sentences;
        List<String> merged = new ArrayList<>();
        merged.add(sentences.getFirst());
        for (int i = 1; i < sentences.size(); i++) {
            String sentence = sentences.get(i);
            if (sentence.length() < minSentenceLength) {
                // Merge with previous
                String prev = merged.removeLast();
                merged.add(prev + " " + sentence);
            } else {
                merged.add(sentence);
            }
        }
        return merged;
    }

    private List<String> buildChunks(List<String> sentences) {
        List<String> chunks = new ArrayList<>();
        int i = 0;

        while (i < sentences.size() && chunks.size() < maxChunks) {
            int chunkStart = i;
            List<String> currentSentences = new ArrayList<>();
            double currentTokens = 0;

            while (i < sentences.size() && currentTokens < targetTokens) {
                String sentence = sentences.get(i);
                currentSentences.add(sentence);
                currentTokens += estimateTokens(sentence);
                i++;
            }

            chunks.add(String.join(" ", currentSentences));

            // Apply overlap: step back by overlapSentences, but guarantee forward progress
            if (i < sentences.size() && overlapSentences > 0) {
                i = Math.max(i - overlapSentences, chunkStart + 1);
            }
        }

        log.debug(
                "Chunked text into {} chunks (from {} sentences)", chunks.size(), sentences.size());
        return chunks;
    }

    private double estimateTokens(String text) {
        int words = text.split("\\s+").length;
        return words * TOKEN_MULTIPLIER;
    }

    private List<Pattern> buildAbbreviationPatterns(String abbreviations) {
        if (abbreviations == null || abbreviations.isBlank()) {
            return List.of();
        }
        List<Pattern> patterns = new ArrayList<>();
        for (String abbr : abbreviations.split(",")) {
            String trimmed = abbr.trim();
            if (!trimmed.isEmpty()) {
                // Escape for regex, case-insensitive
                String escaped = Pattern.quote(trimmed);
                patterns.add(Pattern.compile(escaped, Pattern.CASE_INSENSITIVE));
            }
        }
        return patterns;
    }
}
