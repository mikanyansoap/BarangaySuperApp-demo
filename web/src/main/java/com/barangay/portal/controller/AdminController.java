package com.barangay.portal.controller;

import com.barangay.portal.entity.Profile;
import com.barangay.portal.repository.ProfileRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin")
@CrossOrigin(origins = "*")
public class AdminController {

    @Autowired
    private ProfileRepository profileRepository;

    @Autowired
    private com.barangay.portal.service.GeminiTriageService geminiService;

    @PostMapping("/ai-summary")
    public ResponseEntity<Map<String, String>> getSummary(@RequestBody Map<String, String> payload) {
        String text = payload.get("dataText");
        String summary = geminiService.generateSummary(text != null ? text : "");
        return ResponseEntity.ok(Map.of("summary", summary));
    }

    @GetMapping("/pending-accounts")
    public ResponseEntity<List<Profile>> getPendingAccounts(@RequestParam String psgcCode) {
        List<Profile> pendingProfiles = profileRepository.findByPsgcCodeAndAccountStatus(psgcCode, "pending");
        return ResponseEntity.ok(pendingProfiles);
    }

    @PostMapping("/approve-account/{id}")
    public ResponseEntity<?> updateAccountStatus(@PathVariable UUID id, @RequestBody Map<String, String> payload) {
        String newStatus = payload.get("status"); // 'approved' or 'rejected'
        
        return profileRepository.findById(id).map(profile -> {
            return ResponseEntity.ok(Map.of("message", "Account status updated to " + newStatus));
        }).orElse(ResponseEntity.notFound().build());
    }
}