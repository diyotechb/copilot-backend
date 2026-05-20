package com.example.service.impl;

import com.example.dynamodb.DynamoPersistenceService;
import com.example.service.ResumeService;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import java.io.InputStream;

@Service
public class ResumeServiceDdbImpl implements ResumeService {
    @Autowired
    private DynamoPersistenceService dynamoPersistenceService;

    @Override
    public String extractText(MultipartFile file) {
        try (InputStream is = file.getInputStream(); PDDocument doc = PDDocument.load(is)) {
            return new PDFTextStripper().getText(doc);
        } catch (Exception e) {
            throw new RuntimeException("Failed to extract resume text", e);
        }
    }

    @Override
    public String summarizeResume(String resumeText) {
        // TODO: Call OpenAI to summarize resume text (use OpenAIConversationService)
        return resumeText.length() > 500 ? resumeText.substring(0, 500) + "..." : resumeText;
    }
}
