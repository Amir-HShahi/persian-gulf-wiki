package com.persiangulfwiki.core.subject.service;

import com.persiangulfwiki.core.article.service.SubjectArticleService;
import com.persiangulfwiki.core.audit.service.AuditLogService;
import com.persiangulfwiki.core.subject.dto.SubjectResponse;
import com.persiangulfwiki.core.subject.entity.Subject;
import com.persiangulfwiki.core.subject.exception.SubjectDeletedException;
import com.persiangulfwiki.core.subject.exception.SubjectHasArticlesException;
import com.persiangulfwiki.core.subject.exception.SubjectMergedException;
import com.persiangulfwiki.core.subject.exception.SubjectNotDeletedException;
import com.persiangulfwiki.core.subject.exception.SubjectNotFoundException;
import com.persiangulfwiki.core.subject.exception.SubjectReferencedException;
import com.persiangulfwiki.core.subject.repository.SubjectFactRepository;
import com.persiangulfwiki.core.subject.repository.SubjectRedirectRepository;
import com.persiangulfwiki.core.subject.repository.SubjectRepository;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

// Taking a subject out of the wiki, two ways.
//
// Soft delete (moderator) only sets deleted_at: every public read stops showing the subject and every
// reference to it, and nothing is lost, so a restore brings all of it back. Refused while any article
// is about the subject -- articles never change subject, so their page would point at a subject that
// isn't there; such a subject can only be merged.
//
// Hard delete (admin) removes the row and, through ON DELETE CASCADE, its labels, aliases, facts,
// revision history, moderation tasks and verification marks. Refused while an article is about it or
// another subject's fact refers to it. Links to it in article bodies don't block it: they already
// render as plain text when their subject is missing.
//
// Every write holds the subject row lock, the one approvals and merges take, and is audit-logged.
@Service
@RequiredArgsConstructor
public class SubjectDeletionService {

    private final SubjectRepository subjectRepository;
    private final SubjectRedirectRepository subjectRedirectRepository;
    private final SubjectFactRepository subjectFactRepository;
    private final SubjectService subjectService;
    private final SubjectArticleService subjectArticleService;
    private final SubjectInfoboxCacheService subjectInfoboxCacheService;
    private final AuditLogService auditLogService;

    /**
     * @throws SubjectDeletedException if the subject is already deleted or merged away
     * @throws SubjectHasArticlesException if any article is about it
     */
    @Transactional
    public void softDelete(UUID moderatorId, UUID subjectId) {
        Subject subject = lock(subjectId);
        if (subject.isDeleted()) {
            throw new SubjectDeletedException();
        }
        if (subjectArticleService.hasArticles(subjectId)) {
            throw new SubjectHasArticlesException();
        }
        subject.setDeletedAt(Instant.now());
        subjectRepository.save(subject);
        auditLogService.record(moderatorId, "SUBJECT_SOFT_DELETE", null,
                "subjectId=" + subjectId + ", kind=" + subject.getKind());
        // Other subjects' infoboxes stop showing references to it.
        subjectInfoboxCacheService.evictAll();
    }

    /**
     * @throws SubjectNotDeletedException if the subject is not deleted
     * @throws SubjectMergedException if it was merged away, which cannot be undone
     */
    @Transactional
    public SubjectResponse restore(UUID moderatorId, UUID subjectId) {
        Subject subject = lock(subjectId);
        if (!subject.isDeleted()) {
            throw new SubjectNotDeletedException();
        }
        if (subjectRedirectRepository.existsById(subjectId)) {
            throw new SubjectMergedException();
        }
        subject.setDeletedAt(null);
        subjectRepository.save(subject);
        auditLogService.record(moderatorId, "SUBJECT_RESTORE", null,
                "subjectId=" + subjectId + ", kind=" + subject.getKind());
        subjectInfoboxCacheService.evictAll();
        return subjectService.get(subjectId);
    }

    /**
     * Works on a deleted subject too, merged-away ones included (whose redirect then goes with it).
     *
     * @throws SubjectHasArticlesException if any article is about the subject
     * @throws SubjectReferencedException if another subject's fact refers to it
     */
    @Transactional
    public void hardDelete(UUID adminId, UUID subjectId) {
        Subject subject = lock(subjectId);
        if (subjectArticleService.hasArticles(subjectId)) {
            throw new SubjectHasArticlesException();
        }
        if (subjectFactRepository.existsByValueRefSubjectId(subjectId)) {
            throw new SubjectReferencedException();
        }
        subjectRepository.delete(subject);
        subjectRepository.flush();
        auditLogService.record(adminId, "SUBJECT_HARD_DELETE", null,
                "subjectId=" + subjectId + ", kind=" + subject.getKind());
        subjectInfoboxCacheService.evictAll();
    }

    // The lock a new article's binding waits on (SubjectService.lockForBinding), so no article lands on
    // the subject between the checks above and the write.
    private Subject lock(UUID subjectId) {
        return subjectRepository.findByIdForUpdate(subjectId).orElseThrow(SubjectNotFoundException::new);
    }
}
