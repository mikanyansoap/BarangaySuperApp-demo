package com.barangay.portal.dto;

import lombok.Data;

public class ReportDtos {

    @Data
    public static class CreateReportRequest {
        private String title;
        private String description;
        private String category;
        private String reporterUid;
        private String reporterName;
        private String contactNumber;
        private String location;
        private String imageUrl;
    }

    @Data
    public static class UpdateReportStatusRequest {
        private String status;
        private String adminNotes;
    }
}