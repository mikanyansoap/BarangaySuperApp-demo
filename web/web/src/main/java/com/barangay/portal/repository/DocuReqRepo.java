package com.barangay.portal.repository;

import com.barangay.portal.entity.DocumentRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface DocuReqRepo extends JpaRepository<DocumentRequest, Long> {
    List<DocumentRequest> findByRequesterUid(String requesterUid);
    List<DocumentRequest> findAllByOrderByRequestedAtDesc();
}