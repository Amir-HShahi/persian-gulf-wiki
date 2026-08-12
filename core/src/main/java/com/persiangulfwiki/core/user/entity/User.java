package com.persiangulfwiki.core.user.entity;

import com.persiangulfwiki.core.common.entity.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@SuperBuilder
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class User extends AuditableEntity {

    // Nullable: a Google signup (Case D of GoogleOAuth2SuccessHandler) has no username yet
    // — it's collected when the pending-password-setup account is completed.
    @Column(unique = true, length = 50, columnDefinition = "citext")
    private String username;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "password_hash")
    private String passwordHash;

    @Builder.Default
    @Column(nullable = false)
    private boolean enabled = true;

    @Builder.Default
    @Column(name = "email_verified", nullable = false)
    private boolean emailVerified = false;

    @Column(name = "google_sub", unique = true)
    private String googleSub;
}
