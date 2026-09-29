package com.persiangulfwiki.core.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

// Wiring for media.storage.S3MediaStorage. Two endpoints, because presigned URLs are signed per
// host (see S3MediaStorage): the client uses STORAGE_ENDPOINT, reachable from inside the
// deployment (e.g. http://minio:9000 on the compose network), and the presigner uses
// STORAGE_PUBLIC_ENDPOINT, the host the browser reaches. Locally both are
// http://localhost:9000; on AWS S3 both are the regional endpoint.
@Configuration
public class StorageConfig {

    @Bean(destroyMethod = "close")
    S3Client s3Client(
            @Value("${app.storage.endpoint}") String endpoint,
            @Value("${app.storage.region}") String region,
            @Value("${app.storage.access-key}") String accessKey,
            @Value("${app.storage.secret-key}") String secretKey,
            @Value("${app.storage.path-style}") boolean pathStyle) {
        return S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(pathStyle).build())
                // SDK 2.30+ attaches a CRC32 checksum to every request and validates one on every
                // response by default. S3-compatible stores do not all support that, and nothing
                // here needs it: the one checksum that matters (SHA-256 of an upload) is set
                // explicitly and signed into the presigned PUT.
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .build();
    }

    @Bean(destroyMethod = "close")
    S3Presigner s3Presigner(
            @Value("${app.storage.public-endpoint}") String publicEndpoint,
            @Value("${app.storage.region}") String region,
            @Value("${app.storage.access-key}") String accessKey,
            @Value("${app.storage.secret-key}") String secretKey,
            @Value("${app.storage.path-style}") boolean pathStyle) {
        return S3Presigner.builder()
                .endpointOverride(URI.create(publicEndpoint))
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(pathStyle).build())
                .build();
    }
}
