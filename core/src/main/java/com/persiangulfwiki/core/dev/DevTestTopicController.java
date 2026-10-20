package com.persiangulfwiki.core.dev;

import com.persiangulfwiki.core.dev.dto.DevTestTopicRequest;
import com.persiangulfwiki.core.dev.dto.DevTestTopicResponse;
import com.persiangulfwiki.core.dev.exception.DevTestTopicInUseException;
import com.persiangulfwiki.core.label.entity.Label;
import com.persiangulfwiki.core.label.repository.LabelRepository;
import com.persiangulfwiki.core.topic.entity.Topic;
import com.persiangulfwiki.core.topic.exception.TopicNotFoundException;
import com.persiangulfwiki.core.topic.repository.TopicRepository;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

// Mints throwaway topics, so an E2E suite can assert exact counts on a topic no real article is
// tagged with. Same two gates and the same reasoning as DevTestSubjectController — see that class.
//
// Writes the rows directly rather than through TopicAdminService, so a topic with no name at all
// -- refused by the admin API -- is reachable here, and so is one minted already archived. Its labels go with it through ON DELETE
// CASCADE (V30).
@RestController
@RequestMapping("/api/dev/test-topics")
@Profile("dev")
@RequiredArgsConstructor
@Tag(name = "Dev Test Topics", description = "Dev-profile-only fixture endpoint for E2E suites. Not registered in any other profile.")
public class DevTestTopicController {

    private static final int SLUG_LENGTH = 12;
    private static final String DEFAULT_ICON = "map-pin";
    private static final String DEFAULT_COLOR = "#1288B6";
    // After any hand-made topic, so a minted one lists last.
    private static final int DEFAULT_SORT_ORDER = 1000;

    private final TopicRepository topicRepository;
    private final LabelRepository labelRepository;

    @Operation(summary = "Mint a disposable topic", description = "Every field of the request body is optional; the "
            + "default is a generated slug, icon `map-pin`, color `#1288B6`, sort order 1000, not archived and no name in "
            + "any language. `isArchived` true mints a topic that is already archived.")
    @ApiResponse(responseCode = "201", description = "The created topic.")
    @ApiResponse(responseCode = "400", description = "A field is malformed or too long (see `errors`).")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public DevTestTopicResponse mint(@Valid @RequestBody(required = false) DevTestTopicRequest request) {
        DevTestTopicRequest safeRequest =
                request != null ? request : new DevTestTopicRequest(null, null, null, null, null, null, null, null);
        Topic topic = topicRepository.save(Topic.builder()
                .slug(safeRequest.slug() != null ? safeRequest.slug()
                        : DevTestFixtures.MARKER + "-" + UUID.randomUUID().toString().substring(0, SLUG_LENGTH))
                .icon(Objects.requireNonNullElse(safeRequest.icon(), DEFAULT_ICON))
                // Upper-cased like TopicAdminService does, or ck_topics_color rejects the row.
                .color(Objects.requireNonNullElse(safeRequest.color(), DEFAULT_COLOR).toUpperCase(Locale.ROOT))
                .sortOrder(Objects.requireNonNullElse(safeRequest.sortOrder(), DEFAULT_SORT_ORDER))
                .isArchived(Objects.requireNonNullElse(safeRequest.isArchived(), false))
                .devMarker(DevTestFixtures.MARKER)
                .build());
        saveLabel(topic.getId(), "fa", safeRequest.faLabel());
        saveLabel(topic.getId(), "en", safeRequest.enLabel());
        saveLabel(topic.getId(), "ar", safeRequest.arLabel());
        return new DevTestTopicResponse(topic.getId(), topic.getSlug());
    }

    @Operation(summary = "Delete a minted topic", description = "Deletes a topic minted by this endpoint, with its names. "
            + "Refuses anything else — a topic created through the admin API is reported as 404 rather than deleted. A topic still set on "
            + "an article revision is refused; delete those test articles first.")
    @ApiResponse(responseCode = "204", description = "The topic was deleted.")
    @ApiResponse(responseCode = "404", description = "No such topic, or the id names one this endpoint did not mint.")
    @ApiResponse(responseCode = "409", description = "An article revision still has this topic.")
    @DeleteMapping("/{topicId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void delete(@PathVariable UUID topicId) {
        // The marker check is what keeps this route off the admin-made topics — see
        // DevTestSubjectController.delete.
        Topic topic = topicRepository.findById(topicId)
                .filter(candidate -> DevTestFixtures.MARKER.equals(candidate.getDevMarker()))
                .orElseThrow(TopicNotFoundException::new);
        // Untagging a revision would change an article that may not be a fixture.
        if (topicRepository.isInUse(topicId)) {
            throw new DevTestTopicInUseException("topic " + topicId + " still tags a revision");
        }
        topicRepository.delete(topic);
    }

    private void saveLabel(UUID topicId, String language, @Nullable String value) {
        if (value != null) {
            labelRepository.save(Label.builder().topicId(topicId).language(language).value(value).build());
        }
    }
}
