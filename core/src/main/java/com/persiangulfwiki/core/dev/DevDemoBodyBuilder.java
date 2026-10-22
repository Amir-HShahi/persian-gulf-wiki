package com.persiangulfwiki.core.dev;

import com.persiangulfwiki.core.dev.DevDemoDataset.DemoBlock;
import com.persiangulfwiki.core.dev.DevDemoDataset.DemoSection;
import com.persiangulfwiki.core.dev.DevDemoDataset.DemoTranslation;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;

// Turns one translation of a demo article into a TipTap document: doc > heading / paragraph /
// bulletList > listItem > paragraph, with text nodes carrying bold marks.
//
// Two custom inline nodes, written so they work whichever way the frontend's TipTap extension
// reads them:
//   subjectLink -- core's SubjectLinkService finds `subjectId` directly on the node, while a stock
//                  TipTap node keeps its data under `attrs`, so the id is in both places. The shown
//                  text is a top-level `text` as well as `attrs.label`: search_body_text indexes
//                  every `text` key and nothing under attrs, so this keeps the words searchable.
//   citation    -- `attrs.sourceId` only; ids never reach the search index.
final class DevDemoBodyBuilder {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private DevDemoBodyBuilder() {
    }

    static JsonNode build(DemoTranslation translation, Map<String, UUID> subjectIds, Map<String, UUID> sourceIds) {
        ObjectNode doc = NODES.objectNode().put("type", "doc");
        ArrayNode content = doc.putArray("content");
        for (String paragraph : translation.lead()) {
            content.add(paragraph(paragraph, subjectIds, sourceIds));
        }
        for (DemoSection section : translation.sections()) {
            content.add(heading(2, section.heading()));
            for (DemoBlock block : section.blocks()) {
                if (block.p() != null) {
                    content.add(paragraph(block.p(), subjectIds, sourceIds));
                } else if (block.h3() != null) {
                    content.add(heading(3, block.h3()));
                } else if (block.ul() != null) {
                    ObjectNode list = NODES.objectNode().put("type", "bulletList");
                    ArrayNode items = list.putArray("content");
                    for (String item : block.ul()) {
                        ObjectNode listItem = NODES.objectNode().put("type", "listItem");
                        listItem.putArray("content").add(paragraph(item, subjectIds, sourceIds));
                        items.add(listItem);
                    }
                    content.add(list);
                }
            }
        }
        return doc;
    }

    private static ObjectNode heading(int level, String text) {
        ObjectNode heading = NODES.objectNode().put("type", "heading");
        heading.putObject("attrs").put("level", level);
        heading.putArray("content").add(text(text, false));
        return heading;
    }

    private static ObjectNode paragraph(String markup, Map<String, UUID> subjectIds, Map<String, UUID> sourceIds) {
        ObjectNode paragraph = NODES.objectNode().put("type", "paragraph");
        ArrayNode content = paragraph.putArray("content");
        Matcher matcher = DevDemoDataset.INLINE.matcher(markup);
        int at = 0;
        while (matcher.find()) {
            if (matcher.start() > at) {
                content.add(text(markup.substring(at, matcher.start()), false));
            }
            if (matcher.group(1) != null) {
                String id = subjectIds.get(matcher.group(1)).toString();
                ObjectNode link = NODES.objectNode().put("type", "subjectLink").put("subjectId", id)
                        .put("text", matcher.group(2));
                link.putObject("attrs").put("subjectId", id).put("label", matcher.group(2));
                content.add(link);
            } else if (matcher.group(3) != null) {
                ObjectNode citation = NODES.objectNode().put("type", "citation");
                citation.putObject("attrs").put("sourceId", sourceIds.get(matcher.group(3)).toString());
                content.add(citation);
            } else {
                content.add(text(matcher.group(4), true));
            }
            at = matcher.end();
        }
        if (at < markup.length()) {
            content.add(text(markup.substring(at), false));
        }
        return paragraph;
    }

    private static ObjectNode text(String value, boolean isBold) {
        ObjectNode text = NODES.objectNode().put("type", "text").put("text", value);
        if (isBold) {
            text.putArray("marks").add(NODES.objectNode().put("type", "bold"));
        }
        return text;
    }
}
