package com.persiangulfwiki.core.expertreviewer.service;

import com.persiangulfwiki.core.admin.dto.GrantAction;
import com.persiangulfwiki.core.admin.dto.RoleGrantRequest;
import com.persiangulfwiki.core.admin.service.AdminService;
import com.persiangulfwiki.core.audit.service.AuditLogService;
import com.persiangulfwiki.core.expertreviewer.dto.ApplicationRequest;
import com.persiangulfwiki.core.expertreviewer.dto.ApplicationResponse;
import com.persiangulfwiki.core.expertreviewer.dto.ReviewDecision;
import com.persiangulfwiki.core.expertreviewer.dto.ReviewDecisionRequest;
import com.persiangulfwiki.core.expertreviewer.entity.ApplicationStatus;
import com.persiangulfwiki.core.expertreviewer.entity.ExpertReviewerApplication;
import com.persiangulfwiki.core.expertreviewer.exception.ApplicationAlreadyReviewedException;
import com.persiangulfwiki.core.expertreviewer.exception.ApplicationNotFoundException;
import com.persiangulfwiki.core.expertreviewer.exception.DuplicatePendingApplicationException;
import com.persiangulfwiki.core.expertreviewer.exception.InvalidApplicationStatusException;
import com.persiangulfwiki.core.expertreviewer.repository.ExpertReviewerApplicationRepository;
import com.persiangulfwiki.core.user.entity.Role;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ExpertReviewerService {

    private final ExpertReviewerApplicationRepository applicationRepository;
    private final AdminService adminService;
    private final AuditLogService auditLogService;

    @Transactional
    public ApplicationResponse apply(UUID applicantUserId, ApplicationRequest request) {
        // Checked here first so a duplicate application surfaces as a clean 409 with a
        // readable message, rather than relying solely on the partial unique index
        // (uq_expert_reviewer_applications_pending) to reject it via a raw
        // DataIntegrityViolationException.
        applicationRepository
                .findByApplicantUserIdAndEntityTypeAndStatus(applicantUserId, request.entityType(), ApplicationStatus.PENDING)
                .ifPresent(existing -> {
                    throw new DuplicatePendingApplicationException();
                });

        ExpertReviewerApplication application = ExpertReviewerApplication.builder()
                .applicantUserId(applicantUserId)
                .entityType(request.entityType())
                .justification(request.justification())
                .status(ApplicationStatus.PENDING)
                .build();

        return toResponse(applicationRepository.save(application));
    }

    public List<ApplicationResponse> listApplications(String statusFilter) {
        List<ExpertReviewerApplication> applications = statusFilter == null
                ? applicationRepository.findAll()
                : applicationRepository.findByStatus(parseStatus(statusFilter));

        return applications.stream().map(this::toResponse).toList();
    }

    private ApplicationStatus parseStatus(String statusFilter) {
        try {
            return ApplicationStatus.valueOf(statusFilter.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new InvalidApplicationStatusException("invalid status filter: " + statusFilter);
        }
    }

    @Transactional
    public ApplicationResponse review(UUID reviewingAdminId, UUID applicationId, ReviewDecisionRequest request) {
        ExpertReviewerApplication application = applicationRepository.findById(applicationId)
                .orElseThrow(ApplicationNotFoundException::new);

        if (application.getStatus() != ApplicationStatus.PENDING) {
            throw new ApplicationAlreadyReviewedException();
        }

        ApplicationStatus newStatus = switch (request.decision()) {
            case APPROVE -> ApplicationStatus.APPROVED;
            case REJECT -> ApplicationStatus.REJECTED;
        };

        application.setStatus(newStatus);
        application.setReviewedByUserId(reviewingAdminId);
        application.setReviewedAt(Instant.now());
        applicationRepository.save(application);

        if (request.decision() == ReviewDecision.APPROVE) {
            // Reuse AdminService's own grant mechanism rather than duplicating
            // UserRole-creation logic here — this certifies the exact same EXPERT_REVIEWER
            // role admins can grant manually elsewhere.
            adminService.grantOrRevokeRole(reviewingAdminId, application.getApplicantUserId(),
                    new RoleGrantRequest(Role.EXPERT_REVIEWER, application.getEntityType(), GrantAction.GRANT));
        }

        String detail = "entityType=" + application.getEntityType() + ", applicationId=" + application.getId();
        auditLogService.record(reviewingAdminId, "EXPERT_REVIEWER_APPLICATION_" + request.decision(),
                application.getApplicantUserId(), detail);

        return toResponse(application);
    }

    private ApplicationResponse toResponse(ExpertReviewerApplication application) {
        return new ApplicationResponse(
                application.getId(),
                application.getApplicantUserId(),
                application.getEntityType(),
                application.getJustification(),
                application.getStatus().name(),
                application.getCreatedAt(),
                application.getReviewedAt());
    }
}
