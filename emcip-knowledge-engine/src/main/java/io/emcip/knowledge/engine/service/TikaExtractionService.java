package io.emcip.knowledge.engine.service;

import io.emcip.knowledge.engine.model.ExtractedContent;
import java.io.ByteArrayInputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class TikaExtractionService {

    private static final ExtractedContent EMPTY = new ExtractedContent("", Map.of());

    public ExtractedContent extract(byte[] content) {
        if (content == null || content.length == 0) {
            return EMPTY;
        }

        try {
            BodyContentHandler handler = new BodyContentHandler(-1); // no write limit
            Metadata metadata = new Metadata();
            ParseContext context = new ParseContext();

            new AutoDetectParser()
                    .parse(new ByteArrayInputStream(content), handler, metadata, context);

            String text = handler.toString().trim();
            Map<String, String> metadataMap = extractMetadata(metadata);

            return new ExtractedContent(text, metadataMap);
        } catch (Exception e) {
            log.warn("Tika extraction failed: {}", e.getMessage());
            return EMPTY;
        }
    }

    private Map<String, String> extractMetadata(Metadata metadata) {
        Map<String, String> map = new LinkedHashMap<>();
        String title = metadata.get(TikaCoreProperties.TITLE);
        if (title != null && !title.isBlank()) {
            map.put("documentTitle", title);
        }
        String contentType = metadata.get(Metadata.CONTENT_TYPE);
        if (contentType != null && !contentType.isBlank()) {
            map.put("contentType", contentType);
        }
        String pageCount = metadata.get("xmpTPg:NPages");
        if (pageCount != null && !pageCount.isBlank()) {
            map.put("pageCount", pageCount);
        }
        String author = metadata.get(TikaCoreProperties.CREATOR);
        if (author != null && !author.isBlank()) {
            map.put("author", author);
        }
        return map;
    }
}
