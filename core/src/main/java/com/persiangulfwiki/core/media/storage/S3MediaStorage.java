package com.persiangulfwiki.core.media.storage;

import lombok.RequiredArgsConstructor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ChecksumMode;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.MetadataDirective;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

// Two clients on purpose (see StorageConfig): s3Client talks to the store from inside the
// deployment (STORAGE_ENDPOINT), s3Presigner signs URLs for the host the *browser* reaches
// (STORAGE_PUBLIC_ENDPOINT). SigV4 signs the Host header, so a URL signed for minio:9000 is
// rejected when the browser presents it as localhost:9000 or a public hostname.
@Component
@RequiredArgsConstructor
public class S3MediaStorage implements MediaStorage {

    private static final int NOT_FOUND = 404;
    private static final String PRIVATE_NO_STORE = "private, no-store";
    private static final String FALLBACK_CONTENT_TYPE = "application/octet-stream";

    // Signed, but set by the browser rather than by the client's code -- see PresignedUpload.
    private static final Set<String> BROWSER_SUPPLIED_HEADERS = Set.of("host", "content-length");

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;

    @Value("${app.storage.staging-bucket}")
    private final String stagingBucket;

    @Value("${app.storage.media-bucket}")
    private final String mediaBucket;

    @Override
    public PresignedUpload presignPut(StorageBucket bucket, String key, String contentType, long bytes,
            String sha256Base64, Duration ttl) {
        PutObjectRequest put = PutObjectRequest.builder()
                .bucket(bucketName(bucket))
                .key(key)
                .contentType(contentType)
                .contentLength(bytes)
                .checksumSHA256(sha256Base64)
                .build();
        PresignedPutObjectRequest presigned = s3Presigner.presignPutObject(PutObjectPresignRequest.builder()
                .signatureDuration(ttl)
                .putObjectRequest(put)
                .build());
        return new PresignedUpload(presigned.url().toString(), presigned.expiration(),
                clientHeaders(presigned.signedHeaders()));
    }

    @Override
    public String presignGet(StorageBucket bucket, String key, Duration ttl) {
        return s3Presigner.presignGetObject(GetObjectPresignRequest.builder()
                        .signatureDuration(ttl)
                        .getObjectRequest(GetObjectRequest.builder()
                                .bucket(bucketName(bucket))
                                .key(key)
                                // response-cache-control: signed into the URL, so the client
                                // cannot strip it, and it overrides whatever the object stores.
                                .responseCacheControl(PRIVATE_NO_STORE)
                                .build())
                        .build())
                .url()
                .toString();
    }

    @Override
    public Optional<StoredObject> head(StorageBucket bucket, String key) {
        return headResponse(HeadObjectRequest.builder()
                        .bucket(bucketName(bucket))
                        .key(key)
                        // Without this the store omits the stored checksum from the response.
                        .checksumMode(ChecksumMode.ENABLED)
                        .build())
                .map(response -> new StoredObject(response.contentLength(), response.checksumSHA256()));
    }

    // Cache-Control can only be set on a copy by replacing the object's metadata wholesale, and
    // REPLACE also drops the Content-Type unless it is sent again -- the copy would then be
    // served as binary/octet-stream and a browser would refuse to render it. Hence the HEAD
    // first, to carry the source's type over.
    @Override
    public boolean copy(StorageBucket bucket, String sourceKey, String targetKey, String cacheControl) {
        String bucketName = bucketName(bucket);
        Optional<HeadObjectResponse> source = headResponse(HeadObjectRequest.builder()
                .bucket(bucketName)
                .key(sourceKey)
                .build());
        if (source.isEmpty()) {
            return false;
        }
        String contentType = source.get().contentType();
        s3Client.copyObject(CopyObjectRequest.builder()
                .sourceBucket(bucketName)
                .sourceKey(sourceKey)
                .destinationBucket(bucketName)
                .destinationKey(targetKey)
                .metadataDirective(MetadataDirective.REPLACE)
                .contentType(contentType != null ? contentType : FALLBACK_CONTENT_TYPE)
                .cacheControl(cacheControl)
                .build());
        return true;
    }

    @Override
    public void delete(StorageBucket bucket, String key) {
        s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucketName(bucket)).key(key).build());
    }

    // One ListObjectsV2 page (at most 1000 keys) per DeleteObjects call, which takes at most
    // 1000 -- the two limits match, so a page maps onto one batch.
    @Override
    public int deletePrefix(StorageBucket bucket, String prefix) {
        if (prefix.isBlank()) {
            throw new IllegalArgumentException("refusing to delete by an empty prefix");
        }
        String bucketName = bucketName(bucket);
        int deleted = 0;
        for (ListObjectsV2Response page : s3Client.listObjectsV2Paginator(ListObjectsV2Request.builder()
                .bucket(bucketName)
                .prefix(prefix)
                .build())) {
            List<ObjectIdentifier> keys = page.contents().stream()
                    .map(object -> ObjectIdentifier.builder().key(object.key()).build())
                    .toList();
            if (keys.isEmpty()) {
                continue;
            }
            DeleteObjectsResponse response = s3Client.deleteObjects(DeleteObjectsRequest.builder()
                    .bucket(bucketName)
                    .delete(Delete.builder().objects(keys).quiet(true).build())
                    .build());
            // DeleteObjects reports per-key failures in the body with a 200, not as an
            // exception; surfacing them keeps the caller's "retry next run" contract honest.
            if (!response.errors().isEmpty()) {
                throw new IllegalStateException("could not delete " + response.errors().size()
                        + " object(s) under prefix " + prefix + ": " + response.errors().getFirst().code());
            }
            deleted += keys.size();
        }
        return deleted;
    }

    private Optional<HeadObjectResponse> headResponse(HeadObjectRequest request) {
        try {
            return Optional.of(s3Client.headObject(request));
        } catch (NoSuchKeyException ex) {
            return Optional.empty();
        } catch (S3Exception ex) {
            // A HEAD response has no body, so some stores (MinIO among them) surface a missing
            // key as a bare 404 S3Exception rather than NoSuchKeyException.
            if (ex.statusCode() == NOT_FOUND) {
                return Optional.empty();
            }
            throw ex;
        }
    }

    private String bucketName(StorageBucket bucket) {
        return switch (bucket) {
            case STAGING -> stagingBucket;
            case MEDIA -> mediaBucket;
        };
    }

    private static Map<String, String> clientHeaders(Map<String, List<String>> signedHeaders) {
        Map<String, String> headers = new LinkedHashMap<>();
        signedHeaders.forEach((name, values) -> {
            if (!BROWSER_SUPPLIED_HEADERS.contains(name.toLowerCase())) {
                headers.put(name, String.join(",", values));
            }
        });
        return Map.copyOf(headers);
    }
}
