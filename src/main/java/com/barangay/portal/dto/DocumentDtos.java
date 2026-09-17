package com.barangay.portal.dto;

import lombok.Data;
import java.time.LocalDate;

public class DocumentDtos {

    @Data
    public static class CreateDocumentRequest {
        private String requesterUid;
        private String requesterName;
        private String documentType;
        private String purpose;
    }

    @Data
    public static class UpdateDocumentStatusRequest {
        private String status;
        private LocalDate pickupDate;
        private String adminRemarks;
    }
}