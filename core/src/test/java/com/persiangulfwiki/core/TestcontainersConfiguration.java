package com.persiangulfwiki.core;

import org.mockito.Mockito;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.mail.javamail.JavaMailSender;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	// Test config never sets spring.mail.host, so MailSenderAutoConfiguration never produces a
	// JavaMailSender bean; every @SpringBootTest still needs one to satisfy EmailService's
	// constructor dependency. Tests that care about dispatch (e.g. PasswordFlowIntegrationTests)
	// override this with their own @MockitoBean.
	@Bean
	JavaMailSender javaMailSender() {
		return Mockito.mock(JavaMailSender.class);
	}

	// postgis/postgis, pinned to the same 16-3.4 the compose stacks run. Two deliberate
	// changes from the previous new PostgreSQLContainer(parse("postgres:latest")):
	//
	// asCompatibleSubstituteFor("postgres") is required, not cosmetic — PostgreSQLContainer
	// verifies the image name against its own expected "postgres" and throws on any other
	// repository, so without it every integration test fails before the container starts.
	//
	// The tag is pinned rather than :latest because prod is pinned to 16: PostGIS geometry
	// columns are exactly the kind of thing that behaves differently across majors, and
	// testing on whatever :latest resolved to that morning is how that divergence reaches
	// production unnoticed. The image ships the postgis extension preinstalled; V14 still
	// runs CREATE EXTENSION to actually enable it in this database.
	@Bean
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		return new PostgreSQLContainer(DockerImageName.parse("postgis/postgis:16-3.4")
				.asCompatibleSubstituteFor("postgres"));
	}

	@Bean
	@ServiceConnection(name = "redis")
	GenericContainer<?> redisContainer() {
		return new GenericContainer<>(DockerImageName.parse("redis:latest")).withExposedPorts(6379);
	}

}
