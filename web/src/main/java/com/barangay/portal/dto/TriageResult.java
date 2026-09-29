package com.barangay.portal.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TriageResult {
    private String category;
    private String priority;

    @JsonProperty("ai_valid")
    private Boolean aiValid;

    @JsonProperty("ai_severity_score")
    private Integer aiSeverityScore;

    @JsonProperty("ai_triage_reason")
    private String aiTriageReason;
}