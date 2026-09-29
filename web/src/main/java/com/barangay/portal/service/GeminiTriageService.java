package com.barangay.portal.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class GeminiTriageService {

    @Value("${gemini.api-key}")
    private String apiKey;

    private final RestTemplate restTemplate = new RestTemplate();

    public static class TriageResult {
        private boolean aiValid;
        private int aiSeverityScore;
        private String priority;
        private String triageReason;

        public TriageResult() {}

        public TriageResult(boolean aiValid, int aiSeverityScore, String priority, String triageReason) {
            this.aiValid = aiValid;
            this.aiSeverityScore = aiSeverityScore;
            this.priority = priority;
            this.triageReason = triageReason;
        }

        public boolean isAiValid() { return aiValid; }
        public void setAiValid(boolean aiValid) { this.aiValid = aiValid; }

        public int getAiSeverityScore() { return aiSeverityScore; }
        public void setAiSeverityScore(int aiSeverityScore) { this.aiSeverityScore = aiSeverityScore; }

        public String getPriority() { return priority; }
        public void setPriority(String priority) { this.priority = priority; }

        public String getTriageReason() { return triageReason; }
        public void setTriageReason(String triageReason) { this.triageReason = triageReason; }
    }

    public TriageResult evaluateReport(String title, String category, String description, String photoUrl) {
        String endpoint = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=" + apiKey;

        String prompt = String.format(
            "You are an automated triage AI for a Barangay civic report system.\n" +
            "Analyze this citizen report and evidence:\n" +
            "- Title: %s\n" +
            "- Category: %s\n" +
            "- Description: %s\n" +
            "- Photo Evidence URL: %s\n\n" +
            "Evaluate:\n" +
            "1. Is this report genuine municipal concern or spam/troll? (ai_valid: boolean)\n" +
            "2. Severity score from 0 to 100 based on public hazard, danger, or urgency. (ai_severity_score: integer)\n" +
            "3. Priority level: 'HIGH' (score >= 70 or imminent hazard), 'MEDIUM' (35-69), or 'LOW' (< 35). (priority: string)\n" +
            "4. A concise 1-2 sentence explanation evaluating relevance of the description and photo evidence. (triage_reason: string)\n\n" +
            "Respond ONLY with raw valid JSON in this exact structure:\n" +
            "{\"ai_valid\": true, \"ai_severity_score\": 85, \"priority\": \"HIGH\", \"triage_reason\": \"...\"}",
            title != null ? title : "",
            category != null ? category : "",
            description != null ? description : "",
            (photoUrl != null && !photoUrl.isBlank()) ? photoUrl : "None attached"
        );

        Map<String, Object> part = Collections.singletonMap("text", prompt);
        Map<String, Object> contents = Collections.singletonMap("parts", Collections.singletonList(part));
        Map<String, Object> requestBody = Collections.singletonMap("contents", Collections.singletonList(contents));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

        try {
            ResponseEntity<String> response = restTemplate.postForEntity(endpoint, entity, String.class);
            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                String body = response.getBody();

                boolean valid = !body.contains("\"ai_valid\": false") && !body.contains("\"ai_valid\":false");

                int score = 50;
                Matcher scoreMatcher = Pattern.compile("\"ai_severity_score\"\\s*:\\s*(\\d+)").matcher(body);
                if (scoreMatcher.find()) {
                    score = Integer.parseInt(scoreMatcher.group(1));
                }

                String priority = "MEDIUM";
                Matcher prioMatcher = Pattern.compile("\"priority\"\\s*:\\s*\"([A-Za-z]+)\"").matcher(body);
                if (prioMatcher.find()) {
                    priority = prioMatcher.group(1).toUpperCase();
                }

                String reason = "Automated AI triage completed.";
                Matcher reasonMatcher = Pattern.compile("\"triage_reason\"\\s*:\\s*\"([^\"]+)\"").matcher(body);
                if (reasonMatcher.find()) {
                    reason = reasonMatcher.group(1);
                }

                return new TriageResult(valid, score, priority, reason);
            }
        } catch (Exception ex) {
            System.err.println("Gemini Triage processing error: " + ex.getMessage());
        }

        return new TriageResult(true, 50, "MEDIUM", "Automated AI triage was temporarily unavailable; routed for manual review.");
    }
}