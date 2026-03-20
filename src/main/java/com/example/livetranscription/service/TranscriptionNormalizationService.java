package com.example.livetranscription.service;

import com.example.livetranscription.model.AssemblyAIResponse;
import com.example.livetranscription.model.TranscriptionData;
import org.springframework.stereotype.Service;

import java.util.stream.Collectors;

@Service
public class TranscriptionNormalizationService {

    public TranscriptionData normalize(AssemblyAIResponse response) {
        if (response == null) return null;

        TranscriptionData data = new TranscriptionData();
        
        // Extract text from available V2/V3 fields
        String text = response.getText();
        if (text == null || text.isEmpty()) {
            text = response.getTranscript();
        }
        if (text == null || text.isEmpty()) {
            text = response.getUtterance();
        }
        if ((text == null || text.isEmpty()) && response.getPunctuated() != null) {
            text = response.getPunctuated().getTranscript();
        }
        
        // Join words if text is still empty
        if ((text == null || text.isEmpty()) && response.getWords() != null && !response.getWords().isEmpty()) {
            text = response.getWords().stream()
                    .map(AssemblyAIResponse.Word::getText)
                    .filter(t -> t != null && !t.isEmpty())
                    .collect(Collectors.joining(" "));
        }
        
        data.setText(text);
        
        // Determine end of turn
        boolean eot = false;
        
        // message_type is the most reliable hint in Realtime API
        String mType = response.getMessageType();
        if ("FinalTranscript".equals(mType) || "Turn".equals(mType)) {
            // In V3 Universal, 'Turn' message with non-empty utterance is the final result
            if (response.getUtterance() != null && !response.getUtterance().isEmpty()) {
                text = response.getUtterance();
                eot = true;
            } else if ("FinalTranscript".equals(mType)) {
                eot = true;
            }
        }
        
        // Fallback to other flags
        if (response.getIsFinal() != null) eot = eot || response.getIsFinal();
        if (response.getFinalFlag() != null) eot = eot || response.getFinalFlag();
        if (response.getEndOfTurn() != null) eot = eot || response.getEndOfTurn();
        
        data.setEndOfTurn(eot);
        data.setAudioStart(response.getAudioStart());
        data.setAudioEnd(response.getAudioEnd());
        data.setWords(response.getWords());
        
        return data;
    }
}
