package com.persiangulfwiki.core.article.service;

import com.persiangulfwiki.core.article.dto.SubjectLinkResponse;
import com.persiangulfwiki.core.article.exception.InvalidArticleBodyException;
import com.persiangulfwiki.core.article.exception.InvalidArticleBodyException.Violation;
import com.persiangulfwiki.core.label.dto.LocalizedLabelResponse;
import com.persiangulfwiki.core.label.service.LabelService;
import com.persiangulfwiki.core.subject.entity.Subject;
import com.persiangulfwiki.core.subject.repository.SubjectRepository;
import com.persiangulfwiki.core.subject.service.SubjectService;

import lombok.RequiredArgsConstructor;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import tools.jackson.databind.JsonNode;

// Subject links in an article body: `{ "type": "subjectLink", "subjectId": "…" }` anywhere in the
// tree. The body stores only the id; the text shown is the subject's name in the reader's language,
// resolved on every read, so renaming a subject never breaks a link.
//
// The body is otherwise opaque to the backend (the renderer is the frontend), so any object node
// whose `type` is "subjectLink" is a link, wherever it sits.
@Service
@RequiredArgsConstructor
public class SubjectLinkService {

    private static final String NODE_TYPE = "subjectLink";
    // Bounds the lookup query and the error list for a hostile body. No real article comes close.
    private static final int MAX_SUBJECT_LINKS = 1000;

    private static final String ROOT_PATH = "body";

    private final SubjectRepository subjectRepository;
    private final SubjectService subjectService;
    private final LabelService labelService;
    private final SubjectArticleService subjectArticleService;

    /**
     * Checks every subject link in {@code body} names an existing subject. A deleted or merged-away
     * one counts: an article that already links to it must stay editable, and on read the link shows
     * the merge target, or plain text while the subject is deleted.
     *
     * @throws InvalidArticleBodyException listing each bad link by its path in the body, or the body
     *         itself when it holds more than {@value #MAX_SUBJECT_LINKS} links
     */
    @Transactional(readOnly = true)
    public void validate(JsonNode body) {
        List<Link> links = findLinks(body, MAX_SUBJECT_LINKS + 1);
        if (links.size() > MAX_SUBJECT_LINKS) {
            throw new InvalidArticleBodyException(List.of(new Violation(ROOT_PATH, "validation.subjectLink.tooMany",
                    List.of(String.valueOf(MAX_SUBJECT_LINKS)))));
        }
        Set<UUID> ids = links.stream().map(Link::subjectId).filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<UUID> existing = ids.isEmpty() ? Set.of()
                : subjectRepository.findAllById(ids).stream().map(Subject::getId).collect(Collectors.toSet());

        List<Violation> violations = new ArrayList<>();
        for (Link link : links) {
            if (link.subjectId() == null) {
                violations.add(new Violation(link.path(), "validation.subjectLink.subjectIdInvalid", List.of()));
            } else if (!existing.contains(link.subjectId())) {
                violations.add(new Violation(link.path(), "validation.subjectLink.subjectNotFound", List.of()));
            }
        }
        if (!violations.isEmpty()) {
            throw new InvalidArticleBodyException(violations);
        }
    }

    /**
     * What each subject linked from {@code bodies} shows to a reader of {@code language}: kind, name
     * and page slug, keyed by the id in the body. A link to a merged-away subject shows the subject it
     * was merged into. Links to subjects that no longer exist or are deleted, and malformed ones (only
     * a dev fixture can store those), are absent.
     */
    @Transactional(readOnly = true)
    public Map<UUID, SubjectLinkResponse> resolve(Collection<JsonNode> bodies, String language) {
        Set<UUID> ids = bodies.stream()
                .flatMap(body -> findLinks(body, MAX_SUBJECT_LINKS).stream())
                .map(Link::subjectId)
                .filter(Objects::nonNull)
                .distinct()
                .limit(MAX_SUBJECT_LINKS)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (ids.isEmpty()) {
            return Map.of();
        }
        // Body id -> the subject the link shows. Bodies are history and never rewritten, so a merge
        // is followed here, on every read.
        Map<UUID, UUID> redirects = subjectService.redirectTargets(ids);
        Map<UUID, UUID> shownByLinked = new HashMap<>();
        ids.forEach(id -> shownByLinked.put(id, redirects.getOrDefault(id, id)));
        Map<UUID, Subject> shown = subjectRepository.findAllById(Set.copyOf(shownByLinked.values())).stream()
                .filter(subject -> !subject.isDeleted())
                .collect(Collectors.toMap(Subject::getId, Function.identity()));
        Map<UUID, LocalizedLabelResponse> names = labelService.resolveSubjects(shown.keySet(), language);
        Map<UUID, String> slugs = subjectArticleService.mainArticleSlugs(shown.keySet(), language);

        Map<UUID, SubjectLinkResponse> links = new HashMap<>();
        shownByLinked.forEach((linkedId, shownId) -> {
            Subject subject = shown.get(shownId);
            if (subject != null) {
                links.put(linkedId, new SubjectLinkResponse(subject.getKind(), names.get(shownId), slugs.get(shownId)));
            }
        });
        return Map.copyOf(links);
    }

    /** The entries of {@code links} that {@code body} links to. */
    public Map<UUID, SubjectLinkResponse> retainLinkedFrom(JsonNode body, Map<UUID, SubjectLinkResponse> links) {
        Set<UUID> linked = findLinks(body, MAX_SUBJECT_LINKS).stream().map(Link::subjectId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        return links.entrySet().stream()
                .filter(entry -> linked.contains(entry.getKey()))
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    // The first `limit` links in document order. Walks the tree with an explicit stack rather than
    // recursion, so nesting depth can't overflow it, and keeps each node's path as a parent chain
    // that only becomes a string when an error names it.
    private static List<Link> findLinks(JsonNode body, int limit) {
        List<Link> links = new ArrayList<>();
        Deque<Frame> stack = new ArrayDeque<>();
        stack.push(new Frame(body, null, ROOT_PATH));
        while (!stack.isEmpty() && links.size() < limit) {
            Frame frame = stack.pop();
            JsonNode node = frame.node();
            if (node.isObject()) {
                JsonNode type = node.get("type");
                if (type != null && type.isString() && NODE_TYPE.equals(type.stringValue())) {
                    links.add(new Link(frame, parseSubjectId(node.get("subjectId"))));
                }
                // Reversed so siblings come off the stack in document order, keeping errors in order.
                List<Map.Entry<String, JsonNode>> properties = new ArrayList<>(node.properties());
                for (int i = properties.size() - 1; i >= 0; i--) {
                    Map.Entry<String, JsonNode> property = properties.get(i);
                    if (property.getValue().isContainer()) {
                        stack.push(new Frame(property.getValue(), frame, "." + property.getKey()));
                    }
                }
            } else if (node.isArray()) {
                for (int i = node.size() - 1; i >= 0; i--) {
                    if (node.get(i).isContainer()) {
                        stack.push(new Frame(node.get(i), frame, "[" + i + "]"));
                    }
                }
            }
        }
        return links;
    }

    // A canonical, lowercase-or-uppercase UUID string; UUID.fromString alone also accepts shortened
    // forms such as "1-1-1-1-1".
    private static @Nullable UUID parseSubjectId(@Nullable JsonNode value) {
        if (value == null || !value.isString()) {
            return null;
        }
        try {
            UUID id = UUID.fromString(value.stringValue());
            return id.toString().equalsIgnoreCase(value.stringValue()) ? id : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private record Link(Frame frame, @Nullable UUID subjectId) {

        // e.g. "body.content[2].content[0].subjectId"
        String path() {
            List<String> segments = new ArrayList<>();
            for (Frame current = frame; current != null; current = current.parent()) {
                segments.add(current.segment());
            }
            return String.join("", segments.reversed()) + ".subjectId";
        }
    }

    private record Frame(JsonNode node, @Nullable Frame parent, String segment) {
    }
}
