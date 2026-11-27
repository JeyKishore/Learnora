package com.learnora.learnora.service.providers;

import com.learnora.learnora.model.LearningResource;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.text.Normalizer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
public class MicrosoftLearnProvider implements LearningResourceProvider {

    private static final String CATALOG_URL =
            "https://learn.microsoft.com/api/catalog/";

    private static final long CACHE_DURATION_MS =
            6 * 60 * 60 * 1000L;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    private volatile List<IndexedResource> cachedResources =
            List.of();

    private volatile long lastFetchTime;

    public MicrosoftLearnProvider() {

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();

        this.objectMapper = new ObjectMapper();
    }

    @Override
    public String getPlatform() {
        return "microsoft";
    }

    @Override
    public List<LearningResource> search(
            String query,
            String languages) {

        if (query == null || query.isBlank()) {
            return List.of();
        }

        try {

            long searchStart = System.nanoTime();

            List<IndexedResource> resources =
                    getCatalogResources();

            String normalizedQuery = normalize(query);

            List<String> selectedLanguages =
                    parseLanguages(languages);

            List<Candidate> candidates =
                    new ArrayList<>();

            Set<String> addedUrls =
                    new HashSet<>();

            for (IndexedResource item : resources) {

                if (!matchesLanguage(
                        item,
                        selectedLanguages)) {
                    continue;
                }

                int score = calculateScore(
                        item,
                        normalizedQuery
                );

                if (score < 4) {
                    continue;
                }

                if (!addedUrls.add(item.url())) {
                    continue;
                }

                candidates.add(
                        new Candidate(item, score)
                );
            }

            List<LearningResource> results =
                    candidates.stream()
                            .sorted(
                                    Comparator.comparingInt(
                                            Candidate::score
                                    ).reversed()
                            )
                            .limit(20)
                            .map(candidate ->
                                    createResource(candidate.resource())
                            )
                            .toList();

            long duration =
                    (System.nanoTime() - searchStart)
                            / 1_000_000;

            System.out.println(
                    "Microsoft Learn search took "
                            + duration + " ms"
            );

            return results;

        } catch (Exception e) {

            throw new IllegalStateException(
                    "Unable to retrieve Microsoft Learn resources: "
                            + e.getMessage(),
                    e
            );
        }
    }

    private List<IndexedResource> getCatalogResources()
            throws Exception {

        long currentTime = System.currentTimeMillis();

        if (!cachedResources.isEmpty()
                && currentTime - lastFetchTime
                < CACHE_DURATION_MS) {

            return cachedResources;
        }

        return refreshCatalog();
    }

    private synchronized List<IndexedResource> refreshCatalog()
            throws Exception {

        long currentTime = System.currentTimeMillis();

        if (!cachedResources.isEmpty()
                && currentTime - lastFetchTime
                < CACHE_DURATION_MS) {

            return cachedResources;
        }

        long fetchStart = System.nanoTime();

        System.out.println(
                "Downloading Microsoft Learn catalogue..."
        );

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(CATALOG_URL))
                .timeout(Duration.ofSeconds(45))
                .header("Accept", "application/json")
                .header("User-Agent", "Learnora/1.0")
                .GET()
                .build();

        HttpResponse<String> response =
                httpClient.send(
                        request,
                        HttpResponse.BodyHandlers.ofString()
                );

        if (response.statusCode() != 200) {

            throw new IllegalStateException(
                    "Microsoft catalogue returned HTTP "
                            + response.statusCode()
            );
        }

        JsonNode catalog =
                objectMapper.readTree(response.body());

        List<IndexedResource> indexedResources =
                new ArrayList<>();

        processCatalogSection(
                catalog.path("courses"),
                "course",
                indexedResources
        );

        processCatalogSection(
                catalog.path("learningPaths"),
                "learningPath",
                indexedResources
        );

        processCatalogSection(
                catalog.path("modules"),
                "module",
                indexedResources
        );

        cachedResources =
                List.copyOf(indexedResources);

        lastFetchTime = System.currentTimeMillis();

        long fetchDuration =
                (System.nanoTime() - fetchStart)
                        / 1_000_000;

        System.out.println(
                "Microsoft Learn catalogue loaded: "
                        + cachedResources.size()
                        + " resources"
        );

        System.out.println(
                "Microsoft Learn catalogue processing took "
                        + fetchDuration + " ms"
        );

        return cachedResources;
    }

    private void processCatalogSection(
            JsonNode resources,
            String type,
            List<IndexedResource> indexedResources) {

        if (!resources.isArray()) {
            return;
        }

        for (JsonNode item : resources) {

            String title =
                    item.path("title").asText("");

            String summary =
                    item.path("summary").asText("");

            String url =
                    item.path("url").asText("");

            if (title.isBlank() || url.isBlank()) {
                continue;
            }

            String normalizedTitle =
                    normalize(title);

            String normalizedSummary =
                    normalize(summary);

            List<String> subjects =
                    normalizeArray(item.path("subjects"));

            List<String> products =
                    normalizeArray(item.path("products"));

            List<String> roles =
                    normalizeArray(item.path("roles"));

            List<String> locales =
                    readLocales(item);

            String duration = getDuration(item);

            String level = getLevel(item);

            String language = getLanguage(item);

            String thumbnail =
                    item.path("icon_url").asText("");

            indexedResources.add(
                    new IndexedResource(
                            title,
                            summary,
                            normalizedTitle,
                            normalizedSummary,
                            url,
                            thumbnail,
                            type,
                            duration,
                            level,
                            language,
                            locales,
                            subjects,
                            products,
                            roles
                    )
            );
        }
    }

    private int calculateScore(
            IndexedResource item,
            String normalizedQuery) {

        int score = 0;

        if (item.normalizedTitle()
                .contains(normalizedQuery)) {

            score += 20;
        }

        if (item.normalizedSummary()
                .contains(normalizedQuery)) {

            score += 8;
        }

        String[] keywords =
                normalizedQuery.split("\\s+");

        int matchedKeywords = 0;

        for (String keyword : keywords) {

            if (keyword.length() < 2) {
                continue;
            }

            boolean matched = false;

            if (item.normalizedTitle().contains(keyword)) {
                score += 6;
                matched = true;
            }

            if (item.normalizedSummary().contains(keyword)) {
                score += 2;
                matched = true;
            }

            if (containsKeyword(item.subjects(), keyword)) {
                score += 5;
                matched = true;
            }

            if (containsKeyword(item.products(), keyword)) {
                score += 4;
                matched = true;
            }

            if (containsKeyword(item.roles(), keyword)) {
                score += 2;
                matched = true;
            }

            if (matched) {
                matchedKeywords++;
            }
        }

        if (keywords.length > 1
                && matchedKeywords == 0) {

            return 0;
        }

        return score;
    }

    private boolean containsKeyword(
            List<String> values,
            String keyword) {

        for (String value : values) {

            if (value.contains(keyword)) {
                return true;
            }
        }

        return false;
    }

    private boolean matchesLanguage(
            IndexedResource item,
            List<String> selectedLanguages) {

        if (selectedLanguages.isEmpty()) {
            return true;
        }

        if (item.locales().isEmpty()) {
            return false;
        }

        for (String locale : item.locales()) {

            if (selectedLanguages.contains(locale)) {
                return true;
            }
        }

        return false;
    }

    private List<String> parseLanguages(String languages) {

        if (languages == null || languages.isBlank()) {
            return List.of();
        }

        return java.util.Arrays.stream(
                        languages.split(",")
                )
                .map(this::normalizeLanguage)
                .distinct()
                .toList();
    }

    private List<String> normalizeArray(JsonNode node) {

        List<String> values = new ArrayList<>();

        if (!node.isArray()) {
            return values;
        }

        for (JsonNode value : node) {

            String text = normalize(value.asText());

            if (!text.isBlank()) {
                values.add(text);
            }
        }

        return List.copyOf(values);
    }

    private List<String> readLocales(JsonNode item) {

        JsonNode locales = item.path("locales");

        if (locales.isArray()) {

            List<String> values = new ArrayList<>();

            for (JsonNode locale : locales) {

                String normalized =
                        normalizeLanguage(locale.asText());

                if (!normalized.isBlank()) {
                    values.add(normalized);
                }
            }

            return List.copyOf(values);
        }

        String locale =
                item.path("locale").asText("");

        if (locale.isBlank()) {
            return List.of();
        }

        return List.of(normalizeLanguage(locale));
    }

    private String normalizeLanguage(String language) {

        String normalized = language
                .trim()
                .toLowerCase(Locale.ROOT);

        if (normalized.startsWith("en")) return "en";
        if (normalized.startsWith("ta")) return "ta";
        if (normalized.startsWith("hi")) return "hi";
        if (normalized.startsWith("te")) return "te";
        if (normalized.startsWith("ml")) return "ml";
        if (normalized.startsWith("kn")) return "kn";
        if (normalized.startsWith("fr")) return "fr";
        if (normalized.startsWith("de")) return "de";
        if (normalized.startsWith("es")) return "es";
        if (normalized.startsWith("ja")) return "ja";

        return normalized;
    }

    private String getDuration(JsonNode item) {

        if (item.has("duration_in_hours")) {

            double hours =
                    item.path("duration_in_hours").asDouble();

            return hours + " hours";
        }

        if (item.has("duration_in_minutes")) {

            int minutes =
                    item.path("duration_in_minutes").asInt();

            if (minutes >= 60) {

                return String.format(
                        Locale.ROOT,
                        "%.1f hours",
                        minutes / 60.0
                );
            }

            return minutes + " minutes";
        }

        return "Duration unavailable";
    }

    private String getLevel(JsonNode item) {

        JsonNode levels = item.path("levels");

        if (levels.isArray() && !levels.isEmpty()) {

            return levels.get(0).asText("Not specified");
        }

        return "Not specified";
    }

    private String getLanguage(JsonNode item) {

        JsonNode locales = item.path("locales");

        if (locales.isArray() && !locales.isEmpty()) {

            return locales.get(0).asText();
        }

        return item.path("locale")
                .asText("Unknown language");
    }

    private LearningResource createResource(
            IndexedResource item) {

        String provider = "Microsoft Learn"
                + " · " + formatType(item.type())
                + " · " + item.level()
                + " · " + item.duration()
                + " · " + item.language();

        return new LearningResource(
                item.title(),
                cleanHtml(item.summary()),
                item.url(),
                item.thumbnail(),
                "microsoft",
                "unknown",
                provider
        );
    }

    private String formatType(String type) {

        return switch (type) {
            case "course" -> "Course";
            case "learningPath" -> "Learning Path";
            case "module" -> "Module";
            default -> "Resource";
        };
    }

    private String normalize(String text) {

        return Normalizer.normalize(
                        text == null ? "" : text,
                        Normalizer.Form.NFD
                )
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .trim();
    }

    private String cleanHtml(String html) {

        return html
                .replaceAll("(?i)<br\\s*/?>", " ")
                .replaceAll("(?i)</p>", " ")
                .replaceAll("<[^>]*>", " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private record IndexedResource(
            String title,
            String summary,
            String normalizedTitle,
            String normalizedSummary,
            String url,
            String thumbnail,
            String type,
            String duration,
            String level,
            String language,
            List<String> locales,
            List<String> subjects,
            List<String> products,
            List<String> roles
    ) {
    }

    private record Candidate(
            IndexedResource resource,
            int score
    ) {
    }
}