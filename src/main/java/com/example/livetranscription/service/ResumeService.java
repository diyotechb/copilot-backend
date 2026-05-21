package com.example.livetranscription.service;

import org.springframework.web.multipart.MultipartFile;

public interface ResumeService {
    String extractText(MultipartFile file);
    String summarizeResume(String resumeText);
}
