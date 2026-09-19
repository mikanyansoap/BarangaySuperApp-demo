package com.barangay.portal.controller;

import com.barangay.portal.dto.DocumentDtos.CreateDocumentRequest;
import com.barangay.portal.dto.DocumentDtos.UpdateDocumentStatusRequest;
import com.barangay.portal.entity.DocumentRequest;
import com.barangay.portal.repository.DocuReqRepo;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/documents")
@CrossOrigin(origins = "*")
public class DocuReqController {

    private final DocuReqRepo repository;

    public DocuReqController(DocuReqRepo repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<DocumentRequest> getAllRequests() {
        return repository.findAllByOrderByRequestedAtDesc();
    }

    @PostMapping
    public ResponseEntity<DocumentRequest> submitRequest(@RequestBody CreateDocumentRequest req) {
        DocumentRequest doc = DocumentRequest.builder()
            .requesterUid(req.getRequesterUid())
            .requesterName(req.getRequesterName())
            .documentType(req.getDocumentType())
            .purpose(req.getPurpose())
            .build();
        return ResponseEntity.ok(repository.save(doc));
    }

    @PutMapping("/{id}/status")
    public ResponseEntity<DocumentRequest> updateStatus(
            @PathVariable Long id, 
            @RequestBody UpdateDocumentStatusRequest req) {
        return repository.findById(id).map(doc -> {
            doc.setStatus(req.getStatus());
            doc.setPickupDate(req.getPickupDate());
            doc.setAdminRemarks(req.getAdminRemarks());
            return ResponseEntity.ok(repository.save(doc));
        }).orElse(ResponseEntity.notFound().build());
    }
}