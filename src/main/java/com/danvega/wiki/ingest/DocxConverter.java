package com.danvega.wiki.ingest;

import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.ai.chat.client.ChatClient;

import java.io.FileInputStream;
import java.nio.file.Path;

class DocxConverter implements FileConverter {

    private static final int MAX_CHARS = 12_000;

    @Override
    public boolean supports(Path file) {
        return file.toString().toLowerCase().endsWith(".docx");
    }

    @Override
    public String toMarkdown(Path file, ChatClient chatClient) throws Exception {
        String text;
        try (var fis = new FileInputStream(file.toFile());
             var doc = new XWPFDocument(fis);
             var extractor = new XWPFWordExtractor(doc)) {
            text = extractor.getText();
        }
        if (text.length() > MAX_CHARS) {
            text = text.substring(0, MAX_CHARS) + "\n\n[... truncated ...]";
        }
        return chatClient.prompt().user("""
                Convert the following extracted Word document text into a clean Markdown wiki note.
                Add YAML frontmatter with title and tags inferred from the content.
                Preserve structure, headings, code blocks, and lists.
                Output only the markdown file content, nothing else.

                Text:
                %s
                """.formatted(text)).call().content();
    }
}
