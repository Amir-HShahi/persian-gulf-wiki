package com.persiangulfwiki.core.media.service;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import com.persiangulfwiki.core.jobqueue.JobResult;
import com.persiangulfwiki.core.jobqueue.repository.QueueJobRepository;
import com.persiangulfwiki.core.jobqueue.service.JobQueueService;
import com.persiangulfwiki.core.media.entity.ArticleMedia;
import com.persiangulfwiki.core.media.entity.MediaAttribution;
import com.persiangulfwiki.core.media.entity.MediaKind;
import com.persiangulfwiki.core.media.entity.MediaMetadataVersion;
import com.persiangulfwiki.core.media.entity.MediaVariant;
import com.persiangulfwiki.core.media.entity.MetadataVersionStatus;
import com.persiangulfwiki.core.media.entity.ProcessingStatus;
import com.persiangulfwiki.core.media.entity.PublicationStatus;
import com.persiangulfwiki.core.media.event.MediaVersionAwaitingReviewEvent;
import com.persiangulfwiki.core.media.repository.ArticleMediaRepository;
import com.persiangulfwiki.core.media.repository.MediaMetadataVersionRepository;
import com.persiangulfwiki.core.media.storage.MediaStorage;
import com.persiangulfwiki.core.media.storage.StorageBucket;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

// Every branch of applyResult with the repositories mocked and a real JobQueueService (its
// parsing is part of what decides the branch). The deleted-after-commit staging upload is
// registered as a transaction synchronization, so each test runs with synchronization active and
// fires afterCommit by hand where it matters.
@ExtendWith(MockitoExtension.class)
class MediaProcessingServiceTests {

    private static final long JOB_ID = 42L;
    private static final String SHA256 = "n4bQgYhMfWWaL+qgxVrQFaO/TxsrC4Is0V1sFbDwCgg=";
    private static final String PLACEHOLDER = "data:image/webp;base64,UklGRhoAAABXRUJQVlA4TA0AAAAvAAAAEAcQERGIiP4HAA==";
    private static final ObjectMapper JSON = JsonMapper.builder().build();

    @Mock
    private ArticleMediaRepository articleMediaRepository;

    @Mock
    private MediaMetadataVersionRepository mediaMetadataVersionRepository;

    @Mock
    private QueueJobRepository queueJobRepository;

    @Mock
    private MediaStorage mediaStorage;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private MediaProcessingService mediaProcessingService;

    @BeforeEach
    void setUp() {
        mediaProcessingService = new MediaProcessingService(articleMediaRepository, mediaMetadataVersionRepository,
                new JobQueueService(queueJobRepository, JSON), mediaStorage, eventPublisher);
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    // --- Fixtures ---

    private static ArticleMedia processing(MediaKind kind) {
        return ArticleMedia.builder()
                .id(UUID.randomUUID())
                .articleId(UUID.randomUUID())
                .type(kind)
                .uploadedBy(UUID.randomUUID())
                .declaredContentType("image/jpeg")
                .declaredBytes(2048)
                .declaredSha256(SHA256)
                .processingStatus(ProcessingStatus.PROCESSING)
                .publicationStatus(PublicationStatus.PENDING)
                .processingQueueJobId(JOB_ID)
                .variants(new ArrayList<>())
                .build();
    }

    private void stored(ArticleMedia media) {
        when(articleMediaRepository.findByIdForUpdate(media.getId())).thenReturn(Optional.of(media));
    }

    private static Map<String, Object> envelope(String type, @Nullable String outcome) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("version", 1);
        result.put("job_id", JOB_ID);
        result.put("type", type);
        result.put("outcome", outcome);
        result.put("attempt", 1);
        return result;
    }

    private static Map<String, Object> image(UUID mediaId) {
        String prefix = MediaProcessingService.pendingVariantPrefix(mediaId);
        Map<String, Object> image = new LinkedHashMap<>();
        image.put("width", 4000);
        image.put("height", 3000);
        image.put("format", "jpeg");
        image.put("sha256", SHA256);
        image.put("placeholder", PLACEHOLDER);
        image.put("variants", new ArrayList<>(List.of(
                variant("w640", prefix + "w640.webp"),
                variant("w1280", prefix + "w1280.webp"))));
        return image;
    }

    private static Map<String, Object> variant(String label, String key) {
        Map<String, Object> variant = new HashMap<>();
        variant.put("label", label);
        variant.put("format", "webp");
        variant.put("key", key);
        variant.put("bytes", 20480);
        variant.put("width", 640);
        variant.put("height", 480);
        return variant;
    }

    private static String okResult(ArticleMedia media) {
        Map<String, Object> result = envelope("image", "ok");
        result.put("image", image(media.getId()));
        return JSON.writeValueAsString(result);
    }

    private void applyDone(ArticleMedia media, @Nullable String resultJson) {
        mediaProcessingService.applyResult(media.getId(), JOB_ID, "done", resultJson, null);
    }

    private void assertFailed(ArticleMedia media, String code) {
        assertThat(media.getProcessingStatus()).isEqualTo(ProcessingStatus.FAILED);
        assertThat(media.getFailureCode()).isEqualTo(code);
        assertThat(media.getSha256()).isNull();
        assertThat(media.getVariants()).isEmpty();
        assertThat(media.getPlaceholder()).isNull();
        assertThat(media.getAttribution()).isNull();
        verify(articleMediaRepository).save(media);
        verifyNoInteractions(eventPublisher, mediaStorage);
        assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
    }

    private void assertUntouched(ArticleMedia media, ProcessingStatus status) {
        assertThat(media.getProcessingStatus()).isEqualTo(status);
        assertThat(media.getFailureCode()).isNull();
        verify(articleMediaRepository, never()).save(any());
        verify(articleMediaRepository, never()).saveAndFlush(any());
        verifyNoInteractions(eventPublisher, mediaStorage);
    }

    // --- Rows that are not this item's to apply ---

    @Test
    void ignoresAResultForAnItemThatNoLongerExists() {
        UUID gone = UUID.randomUUID();
        when(articleMediaRepository.findByIdForUpdate(gone)).thenReturn(Optional.empty());

        mediaProcessingService.applyResult(gone, JOB_ID, "done", "{}", null);

        verify(articleMediaRepository, never()).save(any());
        verifyNoInteractions(eventPublisher, mediaStorage);
    }

    @ParameterizedTest
    @ValueSource(strings = {"UPLOADING", "READY", "FAILED"})
    void ignoresAResultForAnItemNoLongerProcessing(ProcessingStatus status) {
        ArticleMedia media = processing(MediaKind.IMAGE);
        media.setProcessingStatus(status);
        stored(media);

        applyDone(media, okResult(media));

        assertUntouched(media, status);
    }

    @Test
    void ignoresAResultForAJobTheItemIsNoLongerWaitingOn() {
        ArticleMedia media = processing(MediaKind.IMAGE);
        stored(media);

        mediaProcessingService.applyResult(media.getId(), JOB_ID + 1, "failed", null, "an older job");

        assertUntouched(media, ProcessingStatus.PROCESSING);
    }

    @Test
    void ignoresAResultForAnItemWaitingOnNoJob() {
        ArticleMedia media = processing(MediaKind.VIDEO);
        media.setProcessingQueueJobId(null);
        stored(media);

        applyDone(media, okResult(media));

        assertUntouched(media, ProcessingStatus.PROCESSING);
    }

    // The poll selects finished rows only; anything else is left for a later poll.
    @ParameterizedTest
    @ValueSource(strings = {"pending", "running", "cancelled"})
    void leavesTheItemAloneWhileItsJobIsNotFinished(String jobStatus) {
        ArticleMedia media = processing(MediaKind.IMAGE);
        stored(media);

        mediaProcessingService.applyResult(media.getId(), JOB_ID, jobStatus, okResult(media), null);

        assertUntouched(media, ProcessingStatus.PROCESSING);
    }

    // --- The worker giving up, and results core refuses ---

    @Test
    void aFailedJobFailsTheItemAsProcessingErrorWhateverItsResultSays() {
        ArticleMedia media = processing(MediaKind.IMAGE);
        stored(media);

        mediaProcessingService.applyResult(media.getId(), JOB_ID, "failed", okResult(media), "x".repeat(2000));

        assertFailed(media, "processing_error");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "null", "[1, 2]", "\"ok\"", "{\"version\": \"one\"}", "{not json"})
    void anUnreadableResultFailsTheItemAsProcessingError(String resultJson) {
        ArticleMedia media = processing(MediaKind.IMAGE);
        stored(media);

        applyDone(media, resultJson);

        assertFailed(media, "processing_error");
    }

    @Test
    void aDoneJobWithNoResultFailsTheItemAsProcessingError() {
        ArticleMedia media = processing(MediaKind.IMAGE);
        stored(media);

        applyDone(media, null);

        assertFailed(media, "processing_error");
    }

    static Stream<Arguments> envelopeProblems() {
        return Stream.of(
                Arguments.of("version 2", (Consumer<Map<String, Object>>) result -> result.put("version", 2)),
                Arguments.of("no version", (Consumer<Map<String, Object>>) result -> result.remove("version")),
                Arguments.of("another job", (Consumer<Map<String, Object>>) result -> result.put("job_id", JOB_ID + 1)),
                Arguments.of("no job id", (Consumer<Map<String, Object>>) result -> result.remove("job_id")),
                Arguments.of("panorama for an image", (Consumer<Map<String, Object>>) result -> result.put("type", "panorama")),
                Arguments.of("old stream type name", (Consumer<Map<String, Object>>) result -> result.put("type", "media.image")),
                Arguments.of("no type", (Consumer<Map<String, Object>>) result -> result.remove("type")),
                Arguments.of("unknown outcome", (Consumer<Map<String, Object>>) result -> result.put("outcome", "deferred")),
                Arguments.of("no outcome", (Consumer<Map<String, Object>>) result -> result.remove("outcome")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("envelopeProblems")
    void anEnvelopeThatDoesNotFitTheItemFailsItAsProcessingError(String problem,
            Consumer<Map<String, Object>> change) {
        ArticleMedia media = processing(MediaKind.IMAGE);
        stored(media);
        Map<String, Object> result = envelope("image", "ok");
        result.put("image", image(media.getId()));
        change.accept(result);

        applyDone(media, JSON.writeValueAsString(result));

        assertFailed(media, "processing_error");
    }

    @Test
    void anImageResultForAPanoramaFailsItAsProcessingError() {
        ArticleMedia media = processing(MediaKind.PANORAMA_360);
        stored(media);

        applyDone(media, okResult(media));

        assertFailed(media, "processing_error");
    }

    // A video has no job type, so no result can ever fit it.
    @ParameterizedTest
    @ValueSource(strings = {"image", "panorama", "video"})
    void anyResultForAVideoFailsItAsProcessingError(String type) {
        ArticleMedia media = processing(MediaKind.VIDEO);
        stored(media);
        Map<String, Object> result = envelope(type, "ok");
        result.put("image", image(media.getId()));

        applyDone(media, JSON.writeValueAsString(result));

        assertFailed(media, "processing_error");
    }

    static Stream<Arguments> imageProblems() {
        return Stream.<Arguments>of(
                imageProblem("no sha256", image -> image.remove("sha256")),
                imageProblem("hex sha256", image -> image.put("sha256", "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08")),
                imageProblem("unpadded sha256", image -> image.put("sha256", SHA256.substring(0, 43))),
                imageProblem("sha256 of 33 bytes", image -> image.put("sha256", "A" + SHA256)),
                imageProblem("svg placeholder", image -> image.put("placeholder", "data:image/svg+xml;base64,PHN2Zz48L3N2Zz4=")),
                imageProblem("placeholder that is a URL", image -> image.put("placeholder", "https://example.org/p.webp")),
                imageProblem("oversized placeholder", image -> image.put("placeholder",
                        "data:image/webp;base64," + "A".repeat(16 * 1024))),
                imageProblem("no variants", image -> image.remove("variants")),
                imageProblem("empty variants", image -> image.put("variants", List.of())),
                imageProblem("33 variants", image -> image.put("variants", IntStream.range(0, 33)
                        .mapToObj(i -> variant("w" + i, prefixOf(image) + "w" + i + ".webp")).toList())),
                imageProblem("null variant", image -> variants(image).add(null)),
                imageProblem("variant without key", image -> variants(image).getFirst().remove("key")),
                imageProblem("variant without bytes", image -> variants(image).getFirst().remove("bytes")),
                imageProblem("variant without width", image -> variants(image).getFirst().remove("width")),
                imageProblem("variant without height", image -> variants(image).getFirst().remove("height")),
                imageProblem("variant without label", image -> variants(image).getFirst().remove("label")),
                imageProblem("blank label", image -> variants(image).getFirst().put("label", "  ")),
                imageProblem("overlong label", image -> variants(image).getFirst().put("label", "w".repeat(41))),
                imageProblem("variant without format", image -> variants(image).getFirst().remove("format")),
                imageProblem("key in another item's prefix", image -> variants(image).getFirst()
                        .put("key", MediaProcessingService.pendingVariantPrefix(UUID.randomUUID()) + "w640.webp")),
                imageProblem("public key", image -> variants(image).getFirst().put("key", "public/x/w640.webp")),
                imageProblem("the bare prefix", image -> variants(image).getFirst().put("key", prefixOf(image))),
                imageProblem("dot-dot key", image -> variants(image).getFirst().put("key", prefixOf(image) + "../../public/x")),
                imageProblem("double slash key", image -> variants(image).getFirst().put("key", prefixOf(image) + "a//b")),
                imageProblem("zero bytes", image -> variants(image).getFirst().put("bytes", 0)),
                imageProblem("negative width", image -> variants(image).getFirst().put("width", -1)),
                imageProblem("zero height", image -> variants(image).getFirst().put("height", 0)));
    }

    private static Arguments imageProblem(String name, Consumer<Map<String, Object>> change) {
        return Arguments.of(name, change);
    }

    // The fixtures' variants live under this key; image() is built per item, so the prefix is
    // read back from the first variant.
    private static String prefixOf(Map<String, Object> image) {
        String key = (String) variants(image).getFirst().get("key");
        return key.substring(0, key.lastIndexOf('/') + 1);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> variants(Map<String, Object> image) {
        return (List<Map<String, Object>>) image.get("variants");
    }

    // The variant-key rule is the security-relevant one: these keys are later copied and deleted
    // by core, so one outside the item's own prefix must never be stored.
    @ParameterizedTest(name = "{0}")
    @MethodSource("imageProblems")
    void anOkResultFailingValidationFailsTheItemAsProcessingErrorAndStoresNothing(String problem,
            Consumer<Map<String, Object>> change) {
        ArticleMedia media = processing(MediaKind.IMAGE);
        stored(media);
        Map<String, Object> image = image(media.getId());
        change.accept(image);
        Map<String, Object> result = envelope("image", "ok");
        result.put("image", image);

        applyDone(media, JSON.writeValueAsString(result));

        assertFailed(media, "processing_error");
        verify(articleMediaRepository, never()).existsVerifiedDuplicate(any(), anyString(), any());
    }

    @Test
    void anOkResultWithoutAnImageBlockFailsTheItemAsProcessingError() {
        ArticleMedia media = processing(MediaKind.IMAGE);
        stored(media);

        applyDone(media, JSON.writeValueAsString(envelope("image", "ok")));

        assertFailed(media, "processing_error");
    }

    // --- done + ok ---

    @Test
    void anOkResultMakesTheItemReadyOpensItsPendingVersionsAndDeletesTheStagingUploadAfterCommit() {
        ArticleMedia media = processing(MediaKind.IMAGE);
        stored(media);
        MediaMetadataVersion first = MediaMetadataVersion.builder().id(UUID.randomUUID()).mediaId(media.getId())
                .versionNumber(1).status(MetadataVersionStatus.PENDING_REVIEW).build();
        MediaMetadataVersion edit = MediaMetadataVersion.builder().id(UUID.randomUUID()).mediaId(media.getId())
                .versionNumber(2).status(MetadataVersionStatus.PENDING_REVIEW).build();
        when(mediaMetadataVersionRepository.findByMediaIdAndStatusOrderByVersionNumberAsc(media.getId(),
                MetadataVersionStatus.PENDING_REVIEW)).thenReturn(List.of(first, edit));
        Map<String, Object> image = image(media.getId());
        image.put("attribution", Map.of("author", "  Maryam‮ Ahmadi ", "license_url", "https://example.org/l"));
        Map<String, Object> result = envelope("image", "ok");
        result.put("image", image);

        applyDone(media, JSON.writeValueAsString(result));

        assertThat(media.getProcessingStatus()).isEqualTo(ProcessingStatus.READY);
        assertThat(media.getFailureCode()).isNull();
        assertThat(media.getSha256()).isEqualTo(SHA256);
        assertThat(media.getPlaceholder()).isEqualTo(PLACEHOLDER);
        String prefix = MediaProcessingService.pendingVariantPrefix(media.getId());
        assertThat(media.getVariants()).containsExactly(
                new MediaVariant("w640", "webp", prefix + "w640.webp", 20480, 640, 480),
                new MediaVariant("w1280", "webp", prefix + "w1280.webp", 20480, 640, 480));
        assertThat(media.getAttribution())
                .isEqualTo(new MediaAttribution("Maryam Ahmadi", null, null, null, "https://example.org/l", null));
        verify(articleMediaRepository).saveAndFlush(media);
        verify(eventPublisher).publishEvent(new MediaVersionAwaitingReviewEvent(first.getId()));
        verify(eventPublisher).publishEvent(new MediaVersionAwaitingReviewEvent(edit.getId()));

        // Nothing is deleted before the commit, and the staging upload alone after it.
        verifyNoInteractions(mediaStorage);
        List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
        assertThat(synchronizations).hasSize(1);
        synchronizations.forEach(TransactionSynchronization::afterCommit);
        verify(mediaStorage).delete(StorageBucket.STAGING, MediaUploadService.stagingKey(media.getId()));
    }

    // A failed staging delete leaves the object behind for good; it must never surface as an
    // error after the item has already committed as READY.
    @Test
    void aFailedStagingDeleteAfterCommitIsSwallowed() {
        ArticleMedia media = processing(MediaKind.IMAGE);
        stored(media);
        doThrow(new IllegalStateException("store down")).when(mediaStorage).delete(eq(StorageBucket.STAGING),
                anyString());

        applyDone(media, okResult(media));

        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        assertThat(media.getProcessingStatus()).isEqualTo(ProcessingStatus.READY);
    }

    @Test
    void anOkPanoramaResultWithoutPlaceholderOrAttributionIsReady() {
        ArticleMedia media = processing(MediaKind.PANORAMA_360);
        stored(media);
        Map<String, Object> image = image(media.getId());
        image.remove("placeholder");
        Map<String, Object> result = envelope("panorama", "ok");
        result.put("image", image);

        applyDone(media, JSON.writeValueAsString(result));

        assertThat(media.getProcessingStatus()).isEqualTo(ProcessingStatus.READY);
        assertThat(media.getPlaceholder()).isNull();
        assertThat(media.getAttribution()).isNull();
        verify(articleMediaRepository).saveAndFlush(media);
    }

    @Test
    void anOkResultWhoseVerifiedHashIsLiveInTheArticleFailsAsDuplicate() {
        ArticleMedia media = processing(MediaKind.IMAGE);
        stored(media);
        when(articleMediaRepository.existsVerifiedDuplicate(media.getArticleId(), SHA256, media.getId()))
                .thenReturn(true);

        applyDone(media, okResult(media));

        assertFailed(media, "duplicate");
        verify(articleMediaRepository, never()).saveAndFlush(any());
    }

    // --- done + rejected ---

    @ParameterizedTest
    @ValueSource(strings = {"unsupported_format", "corrupt_file", "dimensions_too_large", "checksum_mismatch",
            "panorama_aspect_ratio"})
    void aRejectionWithAPipelineCodeKeepsThatCode(String code) {
        ArticleMedia media = processing(MediaKind.IMAGE);
        stored(media);
        Map<String, Object> result = envelope("image", "rejected");
        result.put("reason", Map.of("code", code, "detail", Map.of("limit", "pixels", "max", 100000000)));

        applyDone(media, JSON.writeValueAsString(result));

        assertFailed(media, code);
    }

    // A code core does not know, and one of core's own (which would make the worker's refusal look
    // like a decision core made), are both stored as the generic one.
    @ParameterizedTest
    @ValueSource(strings = {"object_missing", "malformed_payload", "too_blurry", "duplicate", "processing_error",
            "processing_timeout", "upload_mismatch", "rejected", ""})
    void aRejectionWithAnUnknownOrCoreOwnedCodeIsStoredAsGenericRejected(String code) {
        ArticleMedia media = processing(MediaKind.IMAGE);
        stored(media);
        Map<String, Object> result = envelope("image", "rejected");
        result.put("reason", Map.of("code", code));

        applyDone(media, JSON.writeValueAsString(result));

        assertFailed(media, "rejected");
    }

    @Test
    void aRejectionWithoutAReasonOrCodeIsStoredAsGenericRejected() {
        ArticleMedia noReason = processing(MediaKind.IMAGE);
        stored(noReason);
        applyDone(noReason, JSON.writeValueAsString(envelope("image", "rejected")));
        assertThat(noReason.getFailureCode()).isEqualTo("rejected");

        ArticleMedia noCode = processing(MediaKind.PANORAMA_360);
        stored(noCode);
        Map<String, Object> result = envelope("panorama", "rejected");
        result.put("reason", Map.of("detail", Map.of()));
        applyDone(noCode, JSON.writeValueAsString(result));
        assertThat(noCode.getFailureCode()).isEqualTo("rejected");
    }

    // --- failStuckProcessing ---

    @Test
    void failStuckProcessingFailsTheItemsAsTimedOut() {
        Instant threshold = Instant.now();
        when(articleMediaRepository.failProcessingUpdatedBefore(eq(threshold), eq("processing_timeout"), any()))
                .thenReturn(3);

        assertThat(mediaProcessingService.failStuckProcessing(threshold)).isEqualTo(3);
    }

    // --- sanitizeAttribution ---

    private static JobResult.Attribution claimed(@Nullable String author) {
        return new JobResult.Attribution(author, null, null, null, null, null);
    }

    private static @Nullable String cleanedAuthor(String author) {
        MediaAttribution cleaned = MediaProcessingService.sanitizeAttribution(claimed(author));
        return cleaned == null ? null : cleaned.author();
    }

    @Test
    void sanitizeAttributionIsNullForNoClaim() {
        assertThat(MediaProcessingService.sanitizeAttribution(null)).isNull();
    }

    // A line break or tab becomes a space so two words never run together; runs collapse.
    @Test
    void sanitizeAttributionTurnsControlCharactersIntoSingleSpaces() {
        assertThat(cleanedAuthor("Maryam\nAhmadi")).isEqualTo("Maryam Ahmadi");
        assertThat(cleanedAuthor("\t Maryam \r\n\u0000 Ahmadi\u007F ")).isEqualTo("Maryam Ahmadi");
        assertThat(cleanedAuthor("A\u0085B\u001BC")).isEqualTo("A B C");
    }

    // Embeddings, overrides and isolates would let a file make a name display reversed next to
    // the uploader's.
    @Test
    void sanitizeAttributionDropsBidiEmbeddingsOverridesAndIsolates() {
        assertThat(cleanedAuthor("‪a‫b‬c‭d‮e⁦f⁧g⁨h⁩"))
                .isEqualTo("abcdefgh");
        assertThat(cleanedAuthor("‮imdahma maryam")).isEqualTo("imdahma maryam");
    }

    // Persian spelling needs the ZWNJ, and the plain marks reorder nothing on their own.
    @Test
    void sanitizeAttributionKeepsZeroWidthJoinersAndPlainDirectionMarks() {
        assertThat(cleanedAuthor("عکس‌ها")).isEqualTo("عکس‌ها");
        assertThat(cleanedAuthor("a‍b‎c‏d")).isEqualTo("a‍b‎c‏d");
    }

    @Test
    void sanitizeAttributionCutsTo300CodePointsWithoutSplittingASurrogatePair() {
        String emoji = "📷";
        String cut = cleanedAuthor("a".repeat(299) + emoji + emoji);
        assertThat(cut).isEqualTo("a".repeat(299) + emoji);
        assertThat(cut.codePointCount(0, cut.length())).isEqualTo(MediaProcessingService.MAX_ATTRIBUTION_LENGTH);

        // 300 astral code points are 600 UTF-16 units, and still within the cap.
        String astral = emoji.repeat(300);
        assertThat(cleanedAuthor(astral)).isEqualTo(astral);
        // A cut landing after a space leaves no trailing space.
        assertThat(cleanedAuthor("a".repeat(299) + " bcd")).isEqualTo("a".repeat(299));
    }

    @Test
    void sanitizeAttributionKeepsOnlyAWebLicenceUrl() {
        assertThat(licenceUrl("https://creativecommons.org/licenses/by-sa/4.0/"))
                .isEqualTo("https://creativecommons.org/licenses/by-sa/4.0/");
        assertThat(licenceUrl("HTTP://example.org/licence")).isEqualTo("HTTP://example.org/licence");
        assertThat(licenceUrl(" https://example.org/l \n")).isEqualTo("https://example.org/l");
        assertThat(licenceUrl("javascript:alert(1)")).isNull();
        assertThat(licenceUrl("JaVaScRiPt://example.org/%0Aalert(1)")).isNull();
        assertThat(licenceUrl("data:text/html;base64,PHNjcmlwdD4=")).isNull();
        assertThat(licenceUrl("ftp://example.org/licence")).isNull();
        assertThat(licenceUrl("mailto:owner@example.org")).isNull();
        assertThat(licenceUrl("https:///no-host")).isNull();
        assertThat(licenceUrl("//example.org/no-scheme")).isNull();
        assertThat(licenceUrl("http://exa mple.org/")).isNull();
    }

    private static @Nullable String licenceUrl(String url) {
        MediaAttribution cleaned = MediaProcessingService.sanitizeAttribution(
                new JobResult.Attribution("Maryam", null, null, null, url, null));
        assertThat(cleaned).isNotNull();
        return cleaned.licenseUrl();
    }

    @Test
    void sanitizeAttributionCleansEveryField() {
        MediaAttribution cleaned = MediaProcessingService.sanitizeAttribution(new JobResult.Attribution(
                "a\nb", "c‮d", "© e", "CC\tBY", "https://example.org/l", "2024-05-01T10:20:30⁦"));
        assertThat(cleaned).isEqualTo(
                new MediaAttribution("a b", "cd", "© e", "CC BY", "https://example.org/l", "2024-05-01T10:20:30"));
    }

    // Nothing usable left is no attribution at all, not an object of nulls.
    @Test
    void sanitizeAttributionIsNullWhenNothingUsableIsLeft() {
        assertThat(MediaProcessingService.sanitizeAttribution(
                new JobResult.Attribution(null, null, null, null, null, null))).isNull();
        assertThat(MediaProcessingService.sanitizeAttribution(new JobResult.Attribution(
                "  ", "\n\t", "‮⁦", "", "javascript:alert(1)", "\u0000"))).isNull();
    }
}
