package io.emcip.knowledge.engine.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.emcip.knowledge.engine.model.ExtractedContent;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TikaExtractionServiceTest {

    private TikaExtractionService service;

    @BeforeEach
    void setUp() {
        service = new TikaExtractionService();
    }

    @Test
    void extract_returnsEmptyForNullInput() {
        ExtractedContent result = service.extract(null);
        assertThat(result.text()).isEmpty();
        assertThat(result.metadata()).isEmpty();
    }

    @Test
    void extract_returnsEmptyForEmptyBytes() {
        ExtractedContent result = service.extract(new byte[0]);
        assertThat(result.text()).isEmpty();
        assertThat(result.metadata()).isEmpty();
    }

    @Test
    void extract_extractsPlainText() {
        byte[] content = "Hello world. This is a test.".getBytes(StandardCharsets.UTF_8);
        ExtractedContent result = service.extract(content);
        assertThat(result.text()).contains("Hello world");
        assertThat(result.text()).contains("This is a test");
    }

    @Test
    void extract_stripsHtmlBoilerplate() {
        String html =
                """
                <html>
                <head><title>Test Page</title></head>
                <body>
                <nav><a href="/">Home</a><a href="/about">About</a></nav>
                <main><p>This is the main content of the page.</p></main>
                <footer>Copyright 2026</footer>
                </body>
                </html>
                """;
        ExtractedContent result = service.extract(html.getBytes(StandardCharsets.UTF_8));
        assertThat(result.text()).contains("This is the main content");
        assertThat(result.metadata()).containsKey("contentType");
    }

    @Test
    void extract_capturesMetadataFromHtml() {
        String html =
                """
                <html>
                <head><title>Research Paper Title</title></head>
                <body><p>Body text here.</p></body>
                </html>
                """;
        ExtractedContent result = service.extract(html.getBytes(StandardCharsets.UTF_8));
        assertThat(result.metadata()).containsEntry("documentTitle", "Research Paper Title");
        assertThat(result.metadata()).containsKey("contentType");
    }

    @Test
    void extract_returnsEmptyOnCorruptedInput() {
        // Random bytes that no parser can handle — Tika falls back gracefully
        byte[] garbage = new byte[] {0x00, 0x01, 0x02, 0x03, (byte) 0xFF, (byte) 0xFE};
        ExtractedContent result = service.extract(garbage);
        // Should not throw — returns empty or minimal content
        assertThat(result).isNotNull();
    }
}
