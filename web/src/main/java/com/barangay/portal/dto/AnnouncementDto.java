package com.barangay.portal.dto;

import lombok.Data;
import java.time.LocalDate;
import java.util.UUID;

@Data
public class AnnouncementDto {
    private String psgcCode;
    private UUID authorId;
    private String type;
    private String category;
    private String title;
    private String body;
    private LocalDate eventDate;
    private String eventTime;
}