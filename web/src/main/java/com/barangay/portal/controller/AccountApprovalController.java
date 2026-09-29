package com.barangay.portal.controller;

import com.barangay.portal.entity.Profile;
import com.barangay.portal.repository.ProfileRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/accounts")
@CrossOrigin(origins = "*")
public class AccountApprovalController {

    private final ProfileRepository profileRepository;

    public AccountApprovalController(ProfileRepository profileRepository) {
        this.profileRepository = profileRepository;
    }

    @GetMapping("/pending")
    public ResponseEntity<List<Profile>> getPendingAccounts(@RequestParam String psgcCode) {
        return ResponseEntity.ok(profileRepository.findByPsgcCodeAndAccountStatus(psgcCode, "pending"));
    }

    @PutMapping("/{id}/status")
    public ResponseEntity<Profile> updateAccountStatus(@PathVariable UUID id, @RequestBody Map<String, String> request) {
        String newStatus = request.get("status"); // 'approved' or 'rejected'
        return profileRepository.findById(id).map(profile -> {
            profile.setAccountStatus(newStatus);
            return ResponseEntity.ok(profileRepository.save(profile));
        }).orElse(ResponseEntity.notFound().build());
    }
}