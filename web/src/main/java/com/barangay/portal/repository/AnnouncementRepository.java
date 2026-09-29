package com.barangay.portal.repository;

import com.barangay.portal.entity.Announcement;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface AnnouncementRepository extends JpaRepository<Announcement, UUID> {
    List<Announcement> findByPsgcCodeOrderByCreatedAtDesc(String psgcCode);
}