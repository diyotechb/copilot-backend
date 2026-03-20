package com.example.livetranscription.service;

import com.example.livetranscription.model.AssemblyAIResponse;
import com.example.livetranscription.model.TranscriptionData;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class TranscriptionNormalizationService {

    public TranscriptionData normalize(AssemblyAIResponse response) {
        if (response == null) return null;

        TranscriptionData data = new TranscriptionData();
        
        // 1. TEXT EXTRACTION (Prioritize Punctuated Content)
        String text = null;
        if (response.getPunctuated() != null && response.getPunctuated().getTranscript() != null) {
            text = response.getPunctuated().getTranscript();
        }
        
        if (text == null || text.isEmpty()) {
            text = response.getText();
        }
        if (text == null || text.isEmpty()) {
            text = response.getTranscript();
        }
        if (text == null || text.isEmpty()) {
            text = response.getUtterance();
        }
        
        // Join words if text is still empty (essential for some V3 models)
        if ((text == null || text.isEmpty()) && response.getWords() != null && !response.getWords().isEmpty()) {
            text = response.getWords().stream()
                    .map(AssemblyAIResponse.Word::getText)
                    .filter(t -> t != null && !t.isEmpty())
                    .collect(Collectors.joining(" "));
        }

        // 2. FINALIZATION (End of Turn) Logic
        boolean eot = false;
        String mType = response.getMessageType();

        // V2/V3 Whisper legacy
        if ("FinalTranscript".equals(mType)) {
            eot = true;
        }
        
        // V3 Universal: utterance presence is usually final
        boolean hasUtterance = response.getUtterance() != null && !response.getUtterance().isEmpty();
        if (hasUtterance) {
            text = response.getUtterance();
        }

        // Trust explicit flags most for 'end_of_turn' mapping
        if (response.getIsFinal() != null) {
            eot = response.getIsFinal();
        } else if (response.getEndOfTurn() != null) {
            eot = response.getEndOfTurn();
        } else if (response.getFinalFlag() != null) {
            eot = response.getFinalFlag();
        } else if (hasUtterance) {
            eot = true;
        }

        data.setText(text);
        data.setTranscript(text);
        data.setEot(eot);
        data.setFinal(eot);
        data.setAudioStart(response.getAudioStart());
        data.setAudioEnd(response.getAudioEnd());
        data.setWords(response.getWords());
        
        return data;
    }
}
