package com.barangay.portal.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "reports", schema = "public")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Report {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    // Renamed to residentId to perfectly match your findByResidentId repository method!
    @Column(name = "user_id")
    private UUID residentId;

    @Column(name = "psgc_code", nullable = false, length = 50)
    private String psgcCode;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false, length = 50)
    private String category;

    @Column(length = 20)
    private String priority;

    @Column(length = 30)
    private String status = "pending";

    @Column(name = "photo_url", columnDefinition = "TEXT")
    private String photoUrl;

    @Column(name = "ai_valid")
    private Boolean aiValid = true;

    @Column(name = "ai_severity_score")
    private Integer aiSeverityScore = 0;

    @Column(name = "ai_triage_reason", columnDefinition = "TEXT")
    private String aiTriageReason;

    @Column(name = "admin_remarks", columnDefinition = "TEXT")
    private String adminRemarks;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}