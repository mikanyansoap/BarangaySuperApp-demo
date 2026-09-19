package com.barangay.portal.repository;

import com.barangay.portal.entity.Report;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ReportRepository extends JpaRepository<Report, Long> {
    List<Report> findByReporterUid(String reporterUid);
    List<Report> findAllByOrderByCreatedAtDesc();
}