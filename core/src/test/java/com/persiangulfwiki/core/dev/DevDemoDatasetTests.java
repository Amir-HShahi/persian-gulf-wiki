package com.persiangulfwiki.core.dev;

import com.persiangulfwiki.core.dev.DevDemoDataset.DemoArticle;
import com.persiangulfwiki.core.dev.DevDemoDataset.DemoBlock;
import com.persiangulfwiki.core.dev.DevDemoDataset.DemoFact;
import com.persiangulfwiki.core.dev.DevDemoDataset.DemoFactValue;
import com.persiangulfwiki.core.dev.DevDemoDataset.DemoSection;
import com.persiangulfwiki.core.dev.DevDemoDataset.DemoTranslation;
import com.persiangulfwiki.core.subject.entity.SubjectKind;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

// The gate every change to dev/demo/ passes before it can be seeded: no Spring, no database, so it
// runs in a second. What needs the database -- that a payload passes the real approval -- is
// DevDemoContentEndpointIntegrationTests' job.
class DevDemoDatasetTests {

    // V31's seeded topics. Every one must have articles, or its home-page tile shows zero.
    private static final List<String> TOPICS = List.of("marine-life", "plants-and-algae", "islands",
            "ports-and-oil-terminals", "shipping-and-transit", "pollution-and-environment", "history-and-archaeology",
            "economy-and-trade", "geography-and-climate", "culture-and-ethnography");
    // V23's species filter options. Every one must match at least one subject, or the species bank's
    // filter panel shows an option nothing can be found under.
    private static final Map<String, List<String>> SPECIES_OPTIONS = Map.of(
            "category", List.of("FISH", "CRUSTACEAN", "MOLLUSC", "CORAL", "CNIDARIAN", "SEA_PLANT", "MAMMAL", "REPTILE",
                    "BIRD"),
            "iucn_status", List.of("CR", "EN", "VU", "NT", "LC", "DD"),
            "habitat", List.of("CORAL_REEF", "MANGROVE", "MUDFLAT", "SEAGRASS", "PELAGIC"),
            "species_features", List.of("ENDEMIC", "FISHERY", "MIGRATORY"));
    private static final int MIN_WORDS = 300;
    // Arabic attaches articles, prepositions and pronouns to the word, so the same text runs about a
    // fifth shorter in whitespace-separated words than Farsi or English.
    private static final int MIN_WORDS_ARABIC = 240;
    private static final int MIN_ARTICLES_PER_TOPIC = 3;

    private static DevDemoDataset dataset;

    @BeforeAll
    static void load() {
        dataset = DevDemoDataset.load(JsonMapper.builder().build());
    }

    @Test
    void theShippedDatasetLoadsWithNoProblems() {
        assertThat(dataset.articles()).hasSize(50);
        assertThat(dataset.problems(new PathMatchingResourcePatternResolver())).isEmpty();
    }

    @Test
    void everyTranslationIsAFullArticle() {
        List<String> tooShort = new ArrayList<>();
        for (DemoArticle article : dataset.articles()) {
            for (String language : DevDemoDataset.LANGUAGES) {
                DemoTranslation translation = article.translations().get(language);
                int words = DevDemoDataset.wordCount(translation);
                int floor = "ar".equals(language) ? MIN_WORDS_ARABIC : MIN_WORDS;
                if (words < floor) {
                    tooShort.add(article.key() + " [" + language + "] " + words + " words");
                }
                assertThat(translation.sections()).as(article.key() + " [" + language + "] sections").hasSizeGreaterThanOrEqualTo(3);
            }
        }
        assertThat(tooShort).isEmpty();
    }

    @Test
    void everyTopicHasArticles() {
        Map<String, Integer> perTopic = new HashMap<>();
        dataset.articles().forEach(article -> article.topics().forEach(topic -> perTopic.merge(topic, 1, Integer::sum)));
        assertThat(perTopic.keySet()).containsExactlyInAnyOrderElementsOf(TOPICS);
        TOPICS.forEach(topic -> assertThat(perTopic.get(topic)).as(topic).isGreaterThanOrEqualTo(MIN_ARTICLES_PER_TOPIC));
    }

    @Test
    void everySpeciesFilterOptionMatchesASubject() {
        Map<String, Set<String>> used = new HashMap<>();
        dataset.articles().stream()
                .filter(article -> article.subject() != null && article.subject().kind() == SubjectKind.SPECIES)
                .flatMap(article -> article.subject().facts().stream())
                .forEach(fact -> fact.values().stream().map(DemoFactValue::option).filter(Objects::nonNull)
                        .forEach(option -> used.computeIfAbsent(fact.attribute(), _ -> new HashSet<>()).add(option)));
        SPECIES_OPTIONS.forEach((attribute, options) ->
                assertThat(used.get(attribute)).as(attribute).containsAll(options));
    }

    @Test
    void everySubjectKindIsRepresentedAndEverySubjectHasAMainImage() {
        Set<SubjectKind> kinds = new HashSet<>();
        for (DemoArticle article : dataset.articles()) {
            if (article.subject() != null) {
                kinds.add(article.subject().kind());
                assertThat(article.gallery()).as(article.key() + " gallery").isNotEmpty();
            }
        }
        assertThat(kinds).containsExactlyInAnyOrder(SubjectKind.values());
    }

    @Test
    void oneArticleIsFeaturedNowAndOneNext() {
        assertThat(dataset.articles().stream().map(DemoArticle::featured).filter(Objects::nonNull))
                .containsExactlyInAnyOrder(1, 2);
    }

    @Test
    void aLinkToAnUnknownSubjectIsReported() {
        DemoArticle original = dataset.articles().getFirst();
        DemoTranslation fa = original.translations().get("fa");
        DemoTranslation broken = new DemoTranslation(fa.slug(), fa.title(), fa.summary(),
                List.of("[[no-such-subject|nowhere]]"), fa.sections());
        Map<String, DemoTranslation> translations = new HashMap<>(original.translations());
        translations.put("fa", broken);
        DemoArticle withBrokenLink = new DemoArticle(original.key(), original.subject(), original.topics(),
                original.canonicalLanguage(), translations, original.gallery(), original.panorama(),
                original.history(), original.verification(), original.featured());
        List<DemoArticle> articles = new ArrayList<>(dataset.articles());
        articles.set(0, withBrokenLink);

        DevDemoDataset tampered = new DevDemoDataset(dataset.users(), dataset.sources(), dataset.assets(), articles);

        assertThat(tampered.problems(new PathMatchingResourcePatternResolver()))
                .anySatisfy(problem -> assertThat(problem).contains("unknown subject no-such-subject"));
    }

    @Test
    void wordCountIgnoresMarkupButKeepsLinkText() {
        DemoTranslation translation = new DemoTranslation("s", "t", "one two", List.of("**three** [[x|four five]]{{y}}"),
                List.of(new DemoSection("six", List.of(new DemoBlock(null, null, List.of("seven"))))));
        assertThat(DevDemoDataset.wordCount(translation)).isEqualTo(7);
    }

    @Test
    void numericFactsAlwaysNameASource() {
        for (DemoArticle article : dataset.articles()) {
            if (article.subject() == null) {
                continue;
            }
            for (DemoFact fact : article.subject().facts()) {
                for (DemoFactValue value : fact.values()) {
                    if (value.number() != null) {
                        assertThat(value.source()).as(article.key() + " " + fact.attribute()).isNotNull();
                    }
                }
            }
        }
    }
}
