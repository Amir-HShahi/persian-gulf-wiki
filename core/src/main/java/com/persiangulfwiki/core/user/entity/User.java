package com.persiangulfwiki.core.user.entity;

import com.persiangulfwiki.core.common.entity.AuditableEntity;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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

    // The public profile. None of it is asked for at registration; the profile is shown publicly
    // only once fullName and avatarKey are both set.
    @Nullable
    @Column(name = "full_name", length = 100)
    private String fullName;

    @Nullable
    @Column(length = 500)
    private String bio;

    @Nullable
    @Column(length = 150)
    private String affiliation;

    @Nullable
    @Column(name = "avatar_key")
    private String avatarKey;

    // The one outstanding avatar reservation (all five set or all null, CHECK in V36).
    @Nullable
    @Column(name = "avatar_upload_id")
    private UUID avatarUploadId;

    @Nullable
    @Column(name = "avatar_upload_content_type", length = 100)
    private String avatarUploadContentType;

    @Nullable
    @Column(name = "avatar_upload_bytes")
    private Long avatarUploadBytes;

    @Nullable
    @Column(name = "avatar_upload_sha256", length = 44)
    private String avatarUploadSha256;

    @Nullable
    @Column(name = "avatar_upload_expires_at")
    private Instant avatarUploadExpiresAt;

    @Builder.Default
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    @ElementCollection
    @CollectionTable(name = "user_profile_links", joinColumns = @JoinColumn(name = "user_id"))
    @OrderColumn(name = "position")
    @Column(name = "url", nullable = false, length = 500)
    private List<String> links = new ArrayList<>();

    public List<String> getLinks() {
        return List.copyOf(links);
    }

    public void replaceLinks(List<String> newLinks) {
        links.clear();
        links.addAll(newLinks);
    }

    public boolean isProfileComplete() {
        return fullName != null && avatarKey != null;
    }

    public void clearAvatarUpload() {
        avatarUploadId = null;
        avatarUploadContentType = null;
        avatarUploadBytes = null;
        avatarUploadSha256 = null;
        avatarUploadExpiresAt = null;
    }
}
