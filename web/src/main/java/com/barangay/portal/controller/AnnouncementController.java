package com.barangay.portal.controller;

import com.barangay.portal.dto.AnnouncementDto;
import com.barangay.portal.entity.Announcement;
import com.barangay.portal.repository.AnnouncementRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/announcements")
@CrossOrigin(origins = "*")
public class AnnouncementController {

    private final AnnouncementRepository announcementRepository;

    public AnnouncementController(AnnouncementRepository announcementRepository) {
        this.announcementRepository = announcementRepository;
    }

    @GetMapping
    public ResponseEntity<List<Announcement>> getAnnouncementsByBarangay(@RequestParam String psgcCode) {
        return ResponseEntity.ok(announcementRepository.findByPsgcCodeOrderByCreatedAtDesc(psgcCode));
    }

    @PostMapping
    public ResponseEntity<Announcement> createAnnouncement(@RequestBody AnnouncementDto dto) {
        Announcement announcement = Announcement.builder()
                .psgcCode(dto.getPsgcCode())
                .authorId(dto.getAuthorId())
                .type(dto.getType())
                .category(dto.getCategory())
                .title(dto.getTitle())
                .body(dto.getBody())
                .eventDate(dto.getEventDate())
                .eventTime(dto.getEventTime())
                .build();
        return ResponseEntity.ok(announcementRepository.save(announcement));
    }
}