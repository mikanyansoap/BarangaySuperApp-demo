package com.barangay.portal.repository;

import com.barangay.portal.entity.Profile;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface ProfileRepository extends JpaRepository<Profile, UUID> {
    List<Profile> findByPsgcCodeAndAccountStatus(String psgcCode, String accountStatus);
}