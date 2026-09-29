package com.barangay.portal.dto;

import java.util.UUID;

public class ReportDtos {
    private String psgcCode; // Added dynamically
    private String title;
    private String category;
    private String description;
    private String photoUrl;
    private UUID residentId;

    public ReportDtos() {}

    public String getPsgcCode() { return psgcCode; }
    public void setPsgcCode(String psgcCode) { this.psgcCode = psgcCode; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getPhotoUrl() { return photoUrl; }
    public void setPhotoUrl(String photoUrl) { this.photoUrl = photoUrl; }

    public UUID getResidentId() { return residentId; }
    public void setResidentId(UUID residentId) { this.residentId = residentId; }
}