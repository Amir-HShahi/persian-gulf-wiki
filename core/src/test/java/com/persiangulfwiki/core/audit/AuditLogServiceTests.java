package com.persiangulfwiki.core.audit;

import com.persiangulfwiki.core.TestcontainersConfiguration;
import com.persiangulfwiki.core.audit.entity.AuditLog;
import com.persiangulfwiki.core.audit.repository.AuditLogRepository;
import com.persiangulfwiki.core.audit.service.AuditLogService;
import com.persiangulfwiki.core.user.entity.User;
import com.persiangulfwiki.core.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
class AuditLogServiceTests {

    @Autowired
    private AuditLogService auditLogService;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private UserRepository userRepository;

    @Test
    void recordPersistsAuditLogRowWithExpectedFields() {
        UUID actorUserId = userRepository.save(User.builder()
                .username("audit-actor")
                .email("audit-actor@example.com")
                .passwordHash("irrelevant-hash")
                .build()).getId();
        UUID targetUserId = userRepository.save(User.builder()
                .username("audit-target")
                .email("audit-target@example.com")
                .passwordHash("irrelevant-hash")
                .build()).getId();

        auditLogService.record(actorUserId, "USER_ROLE_GRANTED", targetUserId, "granted MODERATOR");

        List<AuditLog> auditLogs = auditLogRepository.findAll();
        assertThat(auditLogs).hasSize(1);

        AuditLog auditLog = auditLogs.get(0);
        assertThat(auditLog.getId()).isNotNull();
        assertThat(auditLog.getActorUserId()).isEqualTo(actorUserId);
        assertThat(auditLog.getAction()).isEqualTo("USER_ROLE_GRANTED");
        assertThat(auditLog.getTargetUserId()).isEqualTo(targetUserId);
        assertThat(auditLog.getDetail()).isEqualTo("granted MODERATOR");
        assertThat(auditLog.getCreatedAt()).isNotNull();
    }

    @Test
    void recordPersistsAuditLogRowWithNullActorForSystemOriginatedEntries() {
        auditLogService.record(null, "ADMIN_BOOTSTRAP", null, "first admin created from seed");

        List<AuditLog> auditLogs = auditLogRepository.findAll();
        assertThat(auditLogs).hasSize(1);
        assertThat(auditLogs.get(0).getActorUserId()).isNull();
        assertThat(auditLogs.get(0).getTargetUserId()).isNull();
    }
}
