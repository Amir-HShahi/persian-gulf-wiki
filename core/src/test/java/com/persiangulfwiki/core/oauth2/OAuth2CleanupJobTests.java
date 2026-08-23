package com.persiangulfwiki.core.oauth2;

import com.persiangulfwiki.core.TestcontainersConfiguration;
import com.persiangulfwiki.core.user.entity.User;
import com.persiangulfwiki.core.user.repository.UserRepository;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class OAuth2CleanupJobTests {

    @Autowired
    private OAuth2CleanupJob cleanupJob;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Value("${app.oauth2.abandoned-account-ttl-hours}")
    private long abandonedAccountTtlHours;

    // createdAt is updatable=false on AuditableEntity, so a plain setter + repository.save()
    // wouldn't persist the backdate — a JPQL bulk update bypasses that restriction. Run in
    // its own transaction since this test class has none open by default.
    private User seedUser(String email, String passwordHash, Instant createdAt) {
        User user = User.builder()
                .email(email)
                .passwordHash(passwordHash)
                .emailVerified(true)
                .build();
        User saved = userRepository.save(user);

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            entityManager.createQuery("update User u set u.createdAt = :createdAt where u.id = :id")
                    .setParameter("createdAt", createdAt)
                    .setParameter("id", saved.getId())
                    .executeUpdate();
        });

        return saved;
    }

    @Test
    void deletesOnlyStaleAbandonedGoogleOnlyRows() {
        User stale = seedUser("stale-sweep@example.com", null,
                Instant.now().minus(abandonedAccountTtlHours + 1, ChronoUnit.HOURS));
        User nonStale = seedUser("fresh-sweep@example.com", null,
                Instant.now().minus(abandonedAccountTtlHours - 1, ChronoUnit.HOURS));
        User normal = seedUser("normal-sweep@example.com", "some-hash",
                Instant.now().minus(abandonedAccountTtlHours + 1, ChronoUnit.HOURS));

        cleanupJob.deleteAbandonedInterimAccounts();

        assertThat(userRepository.findById(stale.getId())).isEmpty();
        assertThat(userRepository.findById(nonStale.getId())).isPresent();
        assertThat(userRepository.findById(normal.getId())).isPresent();
    }
}
