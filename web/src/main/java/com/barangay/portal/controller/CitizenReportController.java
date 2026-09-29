package com.barangay.portal.controller;

import com.barangay.portal.dto.TriageResult;
import com.barangay.portal.entity.Report;
import com.barangay.portal.repository.ReportRepository;
import com.barangay.portal.service.GeminiTriageService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/reports") // Adjust this if your endpoint path is different!
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class CitizenReportController {

    private final ReportRepository reportRepository;
    private final GeminiTriageService geminiTriageService;

    @PostMapping
    public ResponseEntity<Report> submitReport(@RequestBody ReportSubmissionDto dto) {
        if (dto.getPsgcCode() == null || dto.getPsgcCode().isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        TriageResult triage = geminiTriageService.triageReport(dto.getTitle(), dto.getDescription());

        Report report = Report.builder()
                .residentId(dto.getResidentId())
                .psgcCode(dto.getPsgcCode())
                .title(dto.getTitle())
                .description(dto.getDescription())
                .photoUrl(dto.getPhotoUrl())
                .category(triage.getCategory())
                .priority(triage.getPriority())
                .aiValid(triage.getAiValid())
                .aiSeverityScore(triage.getAiSeverityScore())
                .aiTriageReason(triage.getAiTriageReason())
                .status("pending")
                .build();

        Report saved = reportRepository.save(report);
        return ResponseEntity.ok(saved);
    }

    @GetMapping
    public ResponseEntity<List<Report>> getReportsByPsgc(@RequestParam String psgcCode) {
        return ResponseEntity.ok(reportRepository.findByPsgcCodeOrderByCreatedAtDesc(psgcCode));
    }

    @GetMapping("/resident/{residentId}")
    public ResponseEntity<List<Report>> getReportsByResident(@PathVariable UUID residentId) {
        return ResponseEntity.ok(reportRepository.findByResidentId(residentId));
    }

    @GetMapping("/status/{status}")
    public ResponseEntity<List<Report>> getReportsByStatus(@PathVariable String status) {
        return ResponseEntity.ok(reportRepository.findByStatus(status));
    }

    @Data
    public static class ReportSubmissionDto {
        private UUID residentId;
        private String psgcCode;
        private String title;
        private String description;
        private String photoUrl;
    }
}