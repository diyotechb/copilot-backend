package com.example.livetranscription.service;

import com.example.livetranscription.model.AssemblyAIResponse;
import com.example.livetranscription.model.TranscriptionData;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class TranscriptionNormalizationService {

    public TranscriptionData normalize(AssemblyAIResponse response) {
        if (response == null) return null;

        String msgType = response.getType(); // V3: "Begin", "Turn", "Termination"
        String legacyType = response.getMessageType(); // V2: "FinalTranscript", "PartialTranscript"

        // Skip non-content messages
        if ("Begin".equals(msgType) || "Termination".equals(msgType)) return null;

        TranscriptionData data = new TranscriptionData();
        String text = null;
        boolean eot = false;

        if ("Turn".equals(msgType)) {
            // ── AssemblyAI V3 Universal Streaming ──
            // "transcript" holds the cumulative turn text.
            // "turn_is_formatted" = true means the turn ended and text is final/punctuated.
            text = response.getTranscript();
            if (text == null || text.isEmpty()) text = response.getText();
            eot = Boolean.TRUE.equals(response.getTurnIsFormatted());

        } else {
            // ── Legacy V2 / fallback ──
            if (response.getPunctuated() != null && response.getPunctuated().getTranscript() != null) {
                text = response.getPunctuated().getTranscript();
            }
            if (text == null || text.isEmpty()) text = response.getText();
            if (text == null || text.isEmpty()) text = response.getTranscript();
            if (text == null || text.isEmpty()) text = response.getUtterance();

            if ((text == null || text.isEmpty()) && response.getWords() != null && !response.getWords().isEmpty()) {
                text = response.getWords().stream()
                        .map(AssemblyAIResponse.Word::getText)
                        .filter(t -> t != null && !t.isEmpty())
                        .collect(Collectors.joining(" "));
            }

            if ("FinalTranscript".equals(legacyType)) eot = true;
            else if (response.getIsFinal() != null) eot = response.getIsFinal();
            else if (response.getEndOfTurn() != null) eot = response.getEndOfTurn();
            else if (response.getFinalFlag() != null) eot = response.getFinalFlag();
            else if (response.getUtterance() != null && !response.getUtterance().isEmpty()) eot = true;
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
