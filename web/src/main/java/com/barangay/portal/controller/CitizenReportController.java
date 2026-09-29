package com.barangay.portal.controller;

import com.barangay.portal.dto.ReportDtos;
import com.barangay.portal.entity.Report;
import com.barangay.portal.repository.ReportRepository;
import com.barangay.portal.service.GeminiTriageService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/reports")
@CrossOrigin(origins = "*")
public class CitizenReportController {

    private final GeminiTriageService geminiTriageService;
    private final ReportRepository reportRepository;

    public CitizenReportController(GeminiTriageService geminiTriageService, ReportRepository reportRepository) {
        this.geminiTriageService = geminiTriageService;
        this.reportRepository = reportRepository;
    }

    @GetMapping
    public ResponseEntity<List<Report>> getReportsByBarangay(@RequestParam String psgcCode) {
        return ResponseEntity.ok(reportRepository.findByPsgcCodeOrderByCreatedAtDesc(psgcCode));
    }

    @PostMapping
    public ResponseEntity<?> submitCitizenReport(@RequestBody ReportDtos dto) {
        GeminiTriageService.TriageResult triage = geminiTriageService.evaluateReport(
            dto.getTitle(),
            dto.getCategory(),
            dto.getDescription(),
            dto.getPhotoUrl()
        );

        Report report = new Report();
        report.setPsgcCode(dto.getPsgcCode());
        report.setTitle(dto.getTitle());
        report.setCategory(dto.getCategory());
        report.setDescription(dto.getDescription());
        report.setPhotoUrl(dto.getPhotoUrl());
        report.setStatus("pending");

        if (dto.getResidentId() != null) {
            report.setResidentId(dto.getResidentId());
        }

        report.setAiValid(triage.isAiValid());
        report.setAiSeverityScore(triage.getAiSeverityScore());
        report.setPriority(triage.getPriority().toLowerCase());
        report.setAiTriageReason(triage.getTriageReason());

        Report savedReport = reportRepository.save(report);

        return ResponseEntity.ok(savedReport);
    }
}