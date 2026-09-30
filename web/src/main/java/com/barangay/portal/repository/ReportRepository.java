package com.barangay.portal.repository;

import com.barangay.portal.entity.Report;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ReportRepository extends JpaRepository<Report, UUID> {
    List<Report> findByPsgcCodeOrderByCreatedAtDesc(String psgcCode); // Dynamic per barangay
    List<Report> findByResidentId(UUID residentId);
    List<Report> findByStatus(String status);
}