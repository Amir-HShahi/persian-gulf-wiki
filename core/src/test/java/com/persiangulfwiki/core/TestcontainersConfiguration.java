package com.persiangulfwiki.core;

import org.mockito.Mockito;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;

import java.net.URI;

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

	// The same community MinIO build and release the compose stacks run (see docker-compose.yml
	// for why not upstream's image, which no longer exists). asCompatibleSubstituteFor is
	// required for the same reason as postgis above: MinIOContainer checks the image name.
	// A real object store rather than a mocked MediaStorage, because the thing worth testing is
	// exactly what a mock would hide -- that the store enforces the signed checksum and that
	// HeadObject reports it back.
	@Bean
	MinIOContainer minioContainer() {
		return new MinIOContainer(DockerImageName.parse("pgsty/minio:RELEASE.2026-08-04T00-00-00Z")
				.asCompatibleSubstituteFor("minio/minio"));
	}

	// There is no @ServiceConnection for S3, so the container's address and credentials are fed
	// into app.storage.* by hand. Both endpoints are the container's mapped URL: in a test the
	// "browser" (a plain HTTP client) reaches the store exactly where the app does. The buckets
	// named in the test application.yaml are created here, before any bean can need them.
	@Bean
	DynamicPropertyRegistrar storageProperties(MinIOContainer minio) {
		createBucket(minio, "test-staging");
		createBucket(minio, "test-media");
		return registry -> {
			registry.add("app.storage.endpoint", minio::getS3URL);
			registry.add("app.storage.public-endpoint", minio::getS3URL);
			registry.add("app.storage.access-key", minio::getUserName);
			registry.add("app.storage.secret-key", minio::getPassword);
		};
	}

	private static void createBucket(MinIOContainer minio, String bucket) {
		try (S3Client client = S3Client.builder()
				.endpointOverride(URI.create(minio.getS3URL()))
				.region(Region.US_EAST_1)
				.credentialsProvider(StaticCredentialsProvider.create(
						AwsBasicCredentials.create(minio.getUserName(), minio.getPassword())))
				.serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
				.build()) {
			client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
		}
	}

	@Bean
	@ServiceConnection(name = "redis")
	GenericContainer<?> redisContainer() {
		return new GenericContainer<>(DockerImageName.parse("redis:latest")).withExposedPorts(6379);
	}

}
