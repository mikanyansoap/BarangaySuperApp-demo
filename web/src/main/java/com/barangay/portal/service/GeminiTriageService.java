package com.barangay.portal.service;

import com.barangay.portal.dto.TriageResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

@Service
public class GeminiTriageService {

    @Value("${gemini.api.key:}")
    private String apiKey;

    private static final String GEMINI_URL =
        "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=";

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public TriageResult triageReport(String title, String description) {
        if (apiKey == null || apiKey.isBlank()) {
            return fallbackTriage(title, description);
        }

        String prompt = """
            You are a municipal dispatch and triage officer for a Philippine Barangay.
            Analyze the following citizen report submission:
            Title: "%s"
            Description: "%s"

            RULES:
            1. "category": Must be STRICTLY either "Incident" or "Complaint".
               - "Incident": Physical hazards, vehicular collisions, fires, flooding, natural disasters, crimes, or urgent emergencies.
               - "Complaint": Noise nuisances, uncollected waste, neighborhood grievances, minor civil disputes, stray animals.
            2. "priority": "HIGH", "MEDIUM", or "LOW".
            3. "ai_valid": true if a legitimate community concern; false if trolling, test data, spam, or gibberish.
            4. "ai_severity_score": Integer from 0 to 100.
            5. "ai_triage_reason": A concise 1-2 sentence justification for your assessment.

            Respond ONLY in raw JSON matching this schema (no markdown formatting, no code blocks):
            {
              "category": "Incident" | "Complaint",
              "priority": "HIGH" | "MEDIUM" | "LOW",
              "ai_valid": true,
              "ai_severity_score": 85,
              "ai_triage_reason": "..."
            }
            """.formatted(title, description != null ? description : "");

        try {
            Map<String, Object> requestBody = Map.of(
                "contents", List.of(
                    Map.of("parts", List.of(Map.of("text", prompt)))
                )
            );

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(GEMINI_URL + apiKey, entity, String.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode root = objectMapper.readTree(response.getBody());
                String rawText = root.path("candidates").get(0)
                                     .path("content").path("parts").get(0)
                                     .path("text").asText();

                String cleanJson = rawText.replaceAll("(?s)```json\\s*|```", "").trim();
                TriageResult result = objectMapper.readValue(cleanJson, TriageResult.class);

                // Enforce the SQL CHECK constraint so non-conforming outputs never fail database insert
                if (!"Incident".equalsIgnoreCase(result.getCategory()) &&
                    !"Complaint".equalsIgnoreCase(result.getCategory())) {
                    result.setCategory(result.getAiSeverityScore() != null && result.getAiSeverityScore() > 60 ? "Incident" : "Complaint");
                } else {
                    result.setCategory("Incident".equalsIgnoreCase(result.getCategory()) ? "Incident" : "Complaint");
                }

                if (result.getPriority() == null || (!result.getPriority().equalsIgnoreCase("HIGH") &&
                    !result.getPriority().equalsIgnoreCase("MEDIUM") &&
                    !result.getPriority().equalsIgnoreCase("LOW"))) {
                    result.setPriority("MEDIUM");
                } else {
                    result.setPriority(result.getPriority().toUpperCase());
                }

                return result;
            }
        } catch (Exception e) {
            System.err.println("Gemini triage invocation failed: " + e.getMessage());
        }

        return fallbackTriage(title, description);
    }

    private TriageResult fallbackTriage(String title, String description) {
        String combined = (title + " " + (description != null ? description : "")).toLowerCase();
        boolean isIncident = combined.contains("fire") || combined.contains("accident") ||
                             combined.contains("flood") || combined.contains("crash") ||
                             combined.contains("emergency") || combined.contains("dengue") ||
                             combined.contains("collision") || combined.contains("storm") ||
                             combined.contains("tree");

        return TriageResult.builder()
                .category(isIncident ? "Incident" : "Complaint")
                .priority(isIncident ? "HIGH" : "MEDIUM")
                .aiValid(true)
                .aiSeverityScore(isIncident ? 75 : 40)
                .aiTriageReason("Fallback triage applied: Classified via keyword heuristics.")
                .build();
    }
}