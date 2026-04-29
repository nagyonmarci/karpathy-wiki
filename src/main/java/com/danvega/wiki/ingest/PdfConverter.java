package com.danvega.wiki.ingest;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.ai.chat.client.ChatClient;

import java.nio.file.Path;

class PdfConverter implements FileConverter {

    private static final int MAX_CHARS = 12_000;

    @Override
    public boolean supports(Path file) {
        return file.toString().toLowerCase().endsWith(".pdf");
    }

    @Override
    public String toMarkdown(Path file, ChatClient chatClient) throws Exception {
        String text;
        try (PDDocument doc = Loader.loadPDF(file.toFile())) {
            text = new PDFTextStripper().getText(doc);
        }
        if (text.length() > MAX_CHARS) {
            text = text.substring(0, MAX_CHARS) + "\n\n[... truncated ...]";
        }
        return chatClient.prompt().user("""
                Convert the following extracted PDF text into a clean Markdown wiki note.
                Add YAML frontmatter with title and tags inferred from the content.
                Preserve structure, headings, code blocks, and lists.
                Output only the markdown file content, nothing else.

                Text:
                %s
                """.formatted(text)).call().content();
    }
}
