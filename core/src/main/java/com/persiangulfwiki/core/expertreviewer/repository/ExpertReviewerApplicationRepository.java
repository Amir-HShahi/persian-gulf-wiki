package com.persiangulfwiki.core.expertreviewer.repository;

import com.persiangulfwiki.core.expertreviewer.entity.ApplicationStatus;
import com.persiangulfwiki.core.expertreviewer.entity.ExpertReviewerApplication;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ExpertReviewerApplicationRepository extends JpaRepository<ExpertReviewerApplication, UUID> {

    Optional<ExpertReviewerApplication> findByApplicantUserIdAndEntityTypeAndStatus(
            UUID applicantUserId, String entityType, ApplicationStatus status);

    List<ExpertReviewerApplication> findByStatus(ApplicationStatus status);
}
