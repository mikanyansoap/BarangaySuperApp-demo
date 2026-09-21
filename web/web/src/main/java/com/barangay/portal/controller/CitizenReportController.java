package com.barangay.portal.controller;

import com.barangay.portal.dto.ReportDtos.CreateReportRequest;
import com.barangay.portal.dto.ReportDtos.UpdateReportStatusRequest;
import com.barangay.portal.entity.Report;
import com.barangay.portal.repository.ReportRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/reports")
@CrossOrigin(origins = "*")
public class CitizenReportController {

    private final ReportRepository repository;

    public CitizenReportController(ReportRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<Report> getAllReports() {
        return repository.findAllByOrderByCreatedAtDesc();
    }

    @PostMapping
    public ResponseEntity<Report> submitReport(@RequestBody CreateReportRequest req) {
        Report report = Report.builder()
            .title(req.getTitle())
            .description(req.getDescription())
            .category(req.getCategory())
            .reporterUid(req.getReporterUid())
            .reporterName(req.getReporterName())
            .contactNumber(req.getContactNumber())
            .location(req.getLocation())
            .imageUrl(req.getImageUrl())
            .build();
        return ResponseEntity.ok(repository.save(report));
    }

    @PutMapping("/{id}/status")
    public ResponseEntity<Report> updateStatus(
            @PathVariable Long id, 
            @RequestBody UpdateReportStatusRequest req) {
        return repository.findById(id).map(report -> {
            report.setStatus(req.getStatus());
            report.setAdminNotes(req.getAdminNotes());
            return ResponseEntity.ok(repository.save(report));
        }).orElse(ResponseEntity.notFound().build());
    }
}