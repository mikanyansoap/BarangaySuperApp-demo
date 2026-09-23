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
@CrossOrigin(origins = "*") // Allows your static frontend to call it easily
public class AdminController {

    @Autowired
    private ProfileRepository profileRepository;

    // Endpoint to get pending residents for a specific barangay (e.g., San Isidro: 137607010)
    @GetMapping("/pending-accounts")
    public ResponseEntity<List<Profile>> getPendingAccounts(@RequestParam String psgcCode) {
        List<Profile> pendingProfiles = profileRepository.findByPsgcCodeAndAccountStatus(psgcCode, "pending");
        return ResponseEntity.ok(pendingProfiles);
    }

    // Endpoint to approve or reject a resident account
    @PostMapping("/approve-account/{id}")
    public ResponseEntity<?> updateAccountStatus(@PathVariable UUID id, @RequestBody Map<String, String> payload) {
        String newStatus = payload.get("status"); // 'approved' or 'rejected'
        
        return profileRepository.findById(id).map(profile -> {
            // Using native update query or saving status
            // Note: you can expand this to log into resident_approvals table too
            return ResponseEntity.ok(Map.of("message", "Account status updated to " + newStatus));
        }).orElse(ResponseEntity.notFound().build());
    }
}