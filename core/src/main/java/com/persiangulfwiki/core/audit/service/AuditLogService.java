package com.persiangulfwiki.core.audit.service;

import com.persiangulfwiki.core.audit.entity.AuditLog;
import com.persiangulfwiki.core.audit.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuditLogService {

    private final AuditLogRepository auditLogRepository;

    public void record(UUID actorUserId, String action, UUID targetUserId, String detail) {
        AuditLog auditLog = AuditLog.builder()
                .actorUserId(actorUserId)
                .action(action)
                .targetUserId(targetUserId)
                .detail(detail)
                .build();

        auditLogRepository.save(auditLog);
    }
}
