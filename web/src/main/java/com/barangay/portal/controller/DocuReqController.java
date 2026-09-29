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

    // Dynamically fetches document requests for whichever barangay's code is passed
    @GetMapping
    public List<DocumentRequest> getRequestsByBarangay(@RequestParam String psgcCode) {
        return repository.findByPsgcCodeOrderByRequestedAtDesc(psgcCode);
    }

    @PostMapping
    public ResponseEntity<DocumentRequest> submitRequest(@RequestBody CreateDocumentRequest req) {
        DocumentRequest doc = DocumentRequest.builder()
            .psgcCode(req.getPsgcCode())
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