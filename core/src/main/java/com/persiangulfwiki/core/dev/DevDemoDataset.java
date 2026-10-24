package com.persiangulfwiki.core.dev;

import com.persiangulfwiki.core.media.entity.MediaKind;
import com.persiangulfwiki.core.subject.dto.VerificationState;
import com.persiangulfwiki.core.subject.entity.FactRank;
import com.persiangulfwiki.core.subject.entity.SubjectKind;
import com.persiangulfwiki.core.user.entity.Role;

import org.jspecify.annotations.Nullable;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;

import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

// The demo dataset as shipped on the classpath under dev/demo/, parsed and checked. Plain JSON
// files rather than Java literals so a content batch is a data change a reviewer can read, and so
// DevDemoDatasetTests can gate each batch without a Spring context.
//
// load() checks everything that can be checked from the files alone -- unique keys and slugs, every
// cross-reference resolving, every language present, every library file existing -- and fails on
// the first file with problems, naming it. What needs the database (does an infobox fill every
// field its kind defines, does a payload pass SubjectPayloadValidationService) is checked by the
// seed itself, which runs the real approval path.
record DevDemoDataset(List<DemoUser> users, List<DemoSource> sources, List<DemoAsset> assets,
        List<DemoArticle> articles) {

    static final List<String> LANGUAGES = List.of("fa", "en", "ar");

    // Markup inside a paragraph: [[subject-key|shown text]] links a subject, {{source-key}} cites a
    // source, **text** is bold. Kept this small on purpose -- anything richer belongs in the body
    // as its own block, not in an inline syntax only this dataset understands.
    static final Pattern INLINE = Pattern.compile(
            "\\[\\[([a-z0-9-]+)\\|([^\\]]+)]]|\\{\\{([a-z0-9-]+)}}|\\*\\*([^*]+)\\*\\*");

    private static final Pattern KEY = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");
    private static final Pattern VARIANT_FILE = Pattern.compile("[a-z0-9]+\\.webp");
    private static final int MAX_SLUG_LENGTH = 200;
    private static final String SOURCE_URL_ROOT = "https://example.org/demo/";

    record DemoUser(String key, Role role, @Nullable SubjectKind expertise) {
    }

    record DemoSource(String key, String title, String publisher, String url, @Nullable LocalDate publishedOn) {
    }

    record Localized(String fa, String en, String ar) {

        String in(String language) {
            return switch (language) {
                case "fa" -> fa;
                case "en" -> en;
                case "ar" -> ar;
                default -> throw new IllegalArgumentException("unsupported language " + language);
            };
        }
    }

    record DemoCredit(String author, String license, String sourceUrl) {
    }

    record DemoAssetVariant(String size, String file, long bytes, int width, int height) {
    }

    // One library image: every variant imgpipe made for it, plus what a gallery item carries
    // about it. tags pick which subjects it suits ("ISLAND", "mangrove", ...).
    record DemoAsset(String key, MediaKind kind, int width, int height, String sha256, @Nullable String placeholder,
            List<String> tags, Localized caption, DemoCredit credit, List<DemoAssetVariant> variants) {

        long totalBytes() {
            return variants.stream().mapToLong(DemoAssetVariant::bytes).sum();
        }
    }

    record DemoAliases(@Nullable List<String> fa, @Nullable List<String> en, @Nullable List<String> ar) {
    }

    // Mirrors SubjectFactValueRequest, with subject and source named by dataset key instead of id.
    record DemoFactValue(@Nullable BigDecimal number, @Nullable String option, @Nullable String subject,
            @Nullable Boolean bool, @Nullable LocalDate date, @Nullable String text, @Nullable Localized localized,
            @Nullable LocalDate asOf, @Nullable String source, @Nullable FactRank rank) {
    }

    record DemoFact(String attribute, List<DemoFactValue> values) {
    }

    record DemoSubject(SubjectKind kind, Localized names, @Nullable DemoAliases aliases, List<DemoFact> facts,
            @Nullable String geometry) {
    }

    // A block inside a section: exactly one of p (a paragraph, with INLINE markup), h3 (a
    // sub-heading) or ul (a bullet list, each item with INLINE markup).
    record DemoBlock(@Nullable String p, @Nullable String h3, @Nullable List<String> ul) {
    }

    record DemoSection(String heading, List<DemoBlock> blocks) {
    }

    // summary is the article's own summary (ArticleRevision.summary), carried by every revision.
    record DemoTranslation(String slug, String title, String summary, List<String> lead, List<DemoSection> sections) {
    }

    // How the article's history looks: revisions per translation, distinct contributors across
    // them, and which non-canonical translations are left OUTDATED.
    record DemoHistory(int revisions, @Nullable Integer contributors, @Nullable List<String> outdated) {
    }

    record DemoArticle(String key, @Nullable DemoSubject subject, List<String> topics, String canonicalLanguage,
            Map<String, DemoTranslation> translations, @Nullable List<String> gallery, @Nullable String panorama,
            DemoHistory history, DemoBlock pendingEdit, @Nullable VerificationState verification,
            @Nullable Integer featured, @Nullable Boolean machineTranslated) {

        boolean hasSubject() {
            return subject != null;
        }

        // Mock: the non-canonical translations are seeded as machine translations of the canonical
        // one, which becomes the article's source. Nothing is actually translated.
        boolean isMachineTranslated() {
            return Boolean.TRUE.equals(machineTranslated);
        }
    }

    record UsersFile(List<DemoUser> users) {
    }

    record SourcesFile(List<DemoSource> sources) {
    }

    record ManifestFile(List<DemoAsset> assets) {
    }

    static DevDemoDataset load(ObjectMapper objectMapper) {
        ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        String root = DevDemoContent.CLASSPATH_ROOT;
        List<DemoUser> users = read(objectMapper, resolver.getResource("classpath:" + root + "content/users.json"),
                UsersFile.class).users();
        List<DemoSource> sources = read(objectMapper,
                resolver.getResource("classpath:" + root + "content/sources.json"), SourcesFile.class).sources();
        List<DemoAsset> assets = read(objectMapper, resolver.getResource("classpath:" + root + "media/manifest.json"),
                ManifestFile.class).assets();

        List<DemoArticle> articles = new ArrayList<>();
        Resource[] articleFiles;
        try {
            articleFiles = resolver.getResources("classpath*:" + root + "content/articles/**/*.json");
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        for (Resource file : articleFiles) {
            articles.add(read(objectMapper, file, DemoArticle.class));
        }
        // Sorted by key, not by whatever order the classpath scan happened to return, so two runs
        // (and two machines) seed in the same order and the seeded Random makes the same picks.
        articles.sort(Comparator.comparing(DemoArticle::key));

        DevDemoDataset dataset = new DevDemoDataset(List.copyOf(users), List.copyOf(sources), List.copyOf(assets),
                List.copyOf(articles));
        List<String> problems = dataset.problems(resolver);
        if (!problems.isEmpty()) {
            throw new IllegalStateException("the demo dataset is invalid:\n  " + String.join("\n  ", problems));
        }
        return dataset;
    }

    Map<String, DemoArticle> articlesByKey() {
        return articles.stream().collect(Collectors.toMap(DemoArticle::key, Function.identity()));
    }

    Map<String, DemoAsset> assetsByKey() {
        return assets.stream().collect(Collectors.toMap(DemoAsset::key, Function.identity()));
    }

    static String assetPath(DemoAsset asset, DemoAssetVariant variant) {
        return DevDemoContent.CLASSPATH_ROOT + "media/" + asset.key() + "/" + variant.file();
    }

    static int wordCount(DemoTranslation translation) {
        StringBuilder text = new StringBuilder(translation.summary()).append(' ');
        translation.lead().forEach(paragraph -> text.append(paragraph).append(' '));
        for (DemoSection section : translation.sections()) {
            text.append(section.heading()).append(' ');
            for (DemoBlock block : section.blocks()) {
                if (block.p() != null) {
                    text.append(block.p()).append(' ');
                }
                if (block.ul() != null) {
                    block.ul().forEach(item -> text.append(item).append(' '));
                }
            }
        }
        String plain = INLINE.matcher(text).replaceAll(match -> match.group(2) != null ? match.group(2)
                : match.group(4) != null ? match.group(4) : "");
        return plain.isBlank() ? 0 : plain.trim().split("\\s+").length;
    }

    // The text as revision `number` of `revisions` left it. The lead paragraphs and then every block,
    // in order, are the units an editor adds; each revision adds its share, so as long as there are
    // at least as many units as revisions (problems() checks this) every revision changes the body.
    static DemoTranslation asOfRevision(DemoTranslation text, int number, int revisions) {
        int units = (int) Math.ceil(unitCount(text) * (double) number / revisions);
        List<String> lead = text.lead().subList(0, Math.min(units, text.lead().size()));
        int remaining = units - lead.size();
        List<DemoSection> sections = new ArrayList<>();
        for (DemoSection section : text.sections()) {
            if (remaining <= 0) {
                break;
            }
            int blocks = Math.min(remaining, section.blocks().size());
            sections.add(new DemoSection(section.heading(), section.blocks().subList(0, blocks)));
            remaining -= blocks;
        }
        return new DemoTranslation(text.slug(), text.title(), text.summary(), lead, sections);
    }

    static int unitCount(DemoTranslation text) {
        return text.lead().size() + text.sections().stream().mapToInt(section -> section.blocks().size()).sum();
    }

    // Every problem found, each naming the file entry it is in; empty when the dataset is usable.
    List<String> problems(ResourcePatternResolver resolver) {
        List<String> problems = new ArrayList<>();
        checkUsers(problems);
        Set<String> sourceKeys = checkSources(problems);
        Map<String, DemoAsset> assetKeys = checkAssets(problems, resolver);
        checkArticles(problems, sourceKeys, assetKeys);
        return problems;
    }

    private void checkUsers(List<String> problems) {
        Set<String> keys = new HashSet<>();
        for (DemoUser user : users) {
            if (user.key() == null || !KEY.matcher(user.key()).matches() || !keys.add(user.key())) {
                problems.add("users.json: missing, malformed or duplicate key " + user.key());
            }
            if (user.role() == null) {
                problems.add("users.json " + user.key() + ": no role");
            } else if ((user.role() == Role.EXPERT_REVIEWER) != (user.expertise() != null)) {
                problems.add("users.json " + user.key() + ": expertise is required for, and only for, EXPERT_REVIEWER");
            }
        }
        if (users.stream().noneMatch(user -> user.role() == Role.CONTRIBUTOR)) {
            problems.add("users.json: at least one CONTRIBUTOR is required to author revisions");
        }
    }

    private Set<String> checkSources(List<String> problems) {
        Set<String> keys = new HashSet<>();
        for (DemoSource source : sources) {
            String where = "sources.json " + source.key();
            if (source.key() == null || !KEY.matcher(source.key()).matches() || !keys.add(source.key())) {
                problems.add(where + ": missing, malformed or duplicate key");
            }
            if (isBlank(source.title()) || isBlank(source.publisher())) {
                problems.add(where + ": title and publisher are required");
            }
            // Never a deep link made up to look real.
            if (source.url() == null || !source.url().startsWith(SOURCE_URL_ROOT)) {
                problems.add(where + ": url must start with " + SOURCE_URL_ROOT);
            }
        }
        return keys;
    }

    private Map<String, DemoAsset> checkAssets(List<String> problems, ResourcePatternResolver resolver) {
        Map<String, DemoAsset> byKey = new HashMap<>();
        for (DemoAsset asset : assets) {
            String where = "manifest.json " + asset.key();
            if (asset.key() == null || !KEY.matcher(asset.key()).matches() || byKey.putIfAbsent(asset.key(), asset) != null) {
                problems.add(where + ": missing, malformed or duplicate key");
                continue;
            }
            if (asset.kind() != MediaKind.IMAGE && asset.kind() != MediaKind.PANORAMA_360) {
                problems.add(where + ": kind must be IMAGE or PANORAMA_360");
            }
            if (asset.variants() == null || asset.variants().isEmpty()) {
                problems.add(where + ": no variants");
                continue;
            }
            if (asset.caption() == null || anyBlank(asset.caption())) {
                problems.add(where + ": a caption in fa, en and ar is required");
            }
            if (asset.credit() == null || isBlank(asset.credit().license())) {
                problems.add(where + ": a credit with a license is required");
            }
            for (DemoAssetVariant variant : asset.variants()) {
                if (variant.file() == null || !VARIANT_FILE.matcher(variant.file()).matches()) {
                    problems.add(where + ": malformed variant file " + variant.file());
                } else if (!resolver.getResource("classpath:" + assetPath(asset, variant)).exists()) {
                    problems.add(where + ": missing file " + variant.file());
                }
            }
        }
        return byKey;
    }

    private void checkArticles(List<String> problems, Set<String> sourceKeys, Map<String, DemoAsset> assets) {
        Set<String> keys = new HashSet<>();
        Set<String> subjectKeys = new HashSet<>();
        Set<String> slugs = new HashSet<>();
        for (DemoArticle article : articles) {
            if (article.key() == null || !KEY.matcher(article.key()).matches() || !keys.add(article.key())) {
                problems.add("article " + article.key() + ": missing, malformed or duplicate key");
            }
            if (article.hasSubject()) {
                subjectKeys.add(article.key());
            }
        }
        for (DemoArticle article : articles) {
            String where = "article " + article.key();
            checkTranslations(problems, where, article, slugs, subjectKeys, sourceKeys);
            if (article.topics() == null || article.topics().isEmpty()) {
                problems.add(where + ": at least one topic is required");
            }
            if (article.subject() != null) {
                checkSubject(problems, where, article.subject(), subjectKeys, sourceKeys);
            }
            for (String assetKey : article.gallery() != null ? article.gallery() : List.<String>of()) {
                DemoAsset asset = assets.get(assetKey);
                if (asset == null || asset.kind() != MediaKind.IMAGE) {
                    problems.add(where + ": gallery names no library image " + assetKey);
                }
            }
            if (article.panorama() != null) {
                DemoAsset asset = assets.get(article.panorama());
                if (asset == null || asset.kind() != MediaKind.PANORAMA_360) {
                    problems.add(where + ": panorama names no library panorama " + article.panorama());
                }
            }
            if (article.hasSubject() && (article.gallery() == null || article.gallery().isEmpty())) {
                problems.add(where + ": a subject article needs at least one gallery image for its main image");
            }
        }
    }

    private static void checkTranslations(List<String> problems, String where, DemoArticle article, Set<String> slugs,
            Set<String> subjectKeys, Set<String> sourceKeys) {
        if (article.history() == null || article.history().revisions() < 1) {
            problems.add(where + ": history with at least one revision is required");
            return;
        }
        if (article.pendingEdit() == null) {
            problems.add(where + ": a pendingEdit block is required");
        }
        if (article.translations() == null || !article.translations().keySet().equals(Set.copyOf(LANGUAGES))) {
            problems.add(where + ": translations must be exactly fa, en and ar");
            return;
        }
        if (!LANGUAGES.contains(article.canonicalLanguage())) {
            problems.add(where + ": canonicalLanguage must be fa, en or ar");
        }
        for (String language : LANGUAGES) {
            DemoTranslation translation = article.translations().get(language);
            String at = where + " [" + language + "]";
            if (isBlank(translation.slug()) || translation.slug().length() > MAX_SLUG_LENGTH
                    || !slugs.add(translation.slug())) {
                problems.add(at + ": missing, too long or duplicate slug " + translation.slug());
            }
            if (isBlank(translation.title()) || isBlank(translation.summary())) {
                problems.add(at + ": title and summary are required");
            }
            if (translation.sections() == null || translation.sections().isEmpty() || translation.lead() == null) {
                problems.add(at + ": lead and at least one section are required");
                continue;
            }
            List<String> texts = new ArrayList<>(translation.lead());
            boolean isWellFormed = true;
            for (DemoSection section : translation.sections()) {
                if (isBlank(section.heading()) || section.blocks() == null || section.blocks().isEmpty()) {
                    problems.add(at + ": every section needs a heading and blocks");
                    isWellFormed = false;
                    continue;
                }
                for (DemoBlock block : section.blocks()) {
                    checkBlock(problems, at, block, texts);
                }
            }
            if (language.equals(article.canonicalLanguage()) && article.pendingEdit() != null) {
                checkBlock(problems, at + " pendingEdit", article.pendingEdit(), texts);
            }
            if (isWellFormed && unitCount(translation) < article.history().revisions()) {
                problems.add(at + ": " + article.history().revisions() + " revisions but only "
                        + unitCount(translation) + " lead paragraphs and blocks, so some revision would add nothing");
            }
            for (String text : texts) {
                Matcher matcher = INLINE.matcher(text);
                while (matcher.find()) {
                    if (matcher.group(1) != null && !subjectKeys.contains(matcher.group(1))) {
                        problems.add(at + ": link to unknown subject " + matcher.group(1));
                    }
                    if (matcher.group(3) != null && !sourceKeys.contains(matcher.group(3))) {
                        problems.add(at + ": citation of unknown source " + matcher.group(3));
                    }
                }
            }
        }
    }

    private static void checkBlock(List<String> problems, String at, DemoBlock block, List<String> texts) {
        int kinds = (block.p() != null ? 1 : 0) + (block.h3() != null ? 1 : 0) + (block.ul() != null ? 1 : 0);
        if (kinds != 1) {
            problems.add(at + ": a block is exactly one of p, h3 or ul");
        }
        if (block.p() != null) {
            texts.add(block.p());
        }
        if (block.ul() != null) {
            texts.addAll(block.ul());
        }
    }

    private static void checkSubject(List<String> problems, String where, DemoSubject subject, Set<String> subjectKeys,
            Set<String> sourceKeys) {
        if (subject.kind() == null || subject.names() == null || anyBlank(subject.names())) {
            problems.add(where + ": a subject needs a kind and names in fa, en and ar");
        }
        for (DemoFact fact : subject.facts() != null ? subject.facts() : List.<DemoFact>of()) {
            if (isBlank(fact.attribute()) || fact.values() == null || fact.values().isEmpty()) {
                problems.add(where + ": every fact needs an attribute and values");
                continue;
            }
            for (DemoFactValue value : fact.values()) {
                if (value.subject() != null && !subjectKeys.contains(value.subject())) {
                    problems.add(where + " " + fact.attribute() + ": unknown subject " + value.subject());
                }
                if (value.source() != null && !sourceKeys.contains(value.source())) {
                    problems.add(where + " " + fact.attribute() + ": unknown source " + value.source());
                }
            }
        }
    }

    private static <T> T read(ObjectMapper objectMapper, Resource resource, Class<T> type) {
        try (InputStream in = resource.getInputStream()) {
            return objectMapper.readValue(in, type);
        } catch (IOException ex) {
            throw new UncheckedIOException("cannot read demo file " + resource.getDescription(), ex);
        } catch (RuntimeException ex) {
            throw new IllegalStateException("cannot parse demo file " + resource.getDescription(), ex);
        }
    }

    private static boolean anyBlank(Localized text) {
        return isBlank(text.fa()) || isBlank(text.en()) || isBlank(text.ar());
    }

    private static boolean isBlank(@Nullable String value) {
        return value == null || value.isBlank();
    }
}
