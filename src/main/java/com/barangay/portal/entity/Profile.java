package com.barangay.portal.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "profiles", schema = "public")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Profile {
    @Id
    private UUID id;

    @Column(name = "psgc_code")
    private String psgcCode;

    private String role;

    @Column(name = "account_status")
    private String accountStatus;

    @Column(name = "first_name")
    private String firstName;

    @Column(name = "middle_name")
    private String middleName;

    @Column(name = "last_name")
    private String lastName;

    private String suffix;

    @Column(name = "mobile_number")
    private String mobileNumber;

    private String email;

    @Column(name = "current_address")
    private String currentAddress;

    @Column(name = "id_type")
    private String idType;

    @Column(name = "id_number")
    private String idNumber;

    @Column(name = "id_photo_url")
    private String idPhotoUrl;

    @Column(name = "proof_of_residency_url")
    private String proofOfResidencyUrl;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;
}