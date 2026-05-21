package com.example.livetranscription.service.impl;

import com.example.livetranscription.service.ResumeService;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;

@Service
public class ResumeServiceImpl implements ResumeService {

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
        return resumeText.length() > 500 ? resumeText.substring(0, 500) + "..." : resumeText;
    }
}
