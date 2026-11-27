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
import java.util.List;
import java.util.Locale;

@Component
public class IbmSkillsBuildProvider implements LearningResourceProvider {

    private static final String API_URL =
            "https://skillsbuild.org/api/learning-catalog?audience=adult-learner&lang=en-us";

    private static final long CACHE_DURATION_MS =
            6 * 60 * 60 * 1000L;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    private volatile List<IndexedResource> cachedResources =
            List.of();

    private volatile long lastFetchTime;

    public IbmSkillsBuildProvider() {

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();

        this.objectMapper = new ObjectMapper();
    }

    @Override
    public String getPlatform() {
        return "ibm";
    }

    @Override
    public List<LearningResource> search(
            String query,
            String languages) {

        if (query == null || query.isBlank()) {
            return List.of();
        }

        try {

            long startTime = System.nanoTime();

            List<IndexedResource> resources =
                    getCatalogResources();

            String normalizedQuery = normalize(query);

            List<String> selectedLanguages =
                    parseLanguages(languages);

            List<Candidate> candidates =
                    new ArrayList<>();

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

                if (score <= 0) {
                    continue;
                }

                candidates.add(
                        new Candidate(item.resource(), score)
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
                            .map(Candidate::resource)
                            .toList();

            long duration =
                    (System.nanoTime() - startTime)
                            / 1_000_000;

            System.out.println(
                    "IBM SkillsBuild search took "
                            + duration + " ms"
            );

            System.out.println(
                    "IBM SkillsBuild results found: "
                            + results.size()
            );

            return results;

        } catch (Exception e) {

            throw new IllegalStateException(
                    "Unable to retrieve IBM SkillsBuild resources: "
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
                "Downloading IBM SkillsBuild catalogue..."
        );

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_URL))
                .timeout(Duration.ofSeconds(30))
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
                    "IBM catalog request failed: "
                            + response.statusCode()
            );
        }

        JsonNode root =
                objectMapper.readTree(response.body());

        JsonNode items = root.path("items");

        if (!items.isArray()) {

            throw new IllegalStateException(
                    "IBM catalogue does not contain an items array."
            );
        }

        List<IndexedResource> indexedResources =
                new ArrayList<>();

        for (JsonNode item : items) {

            String title =
                    item.path("title").asText("");

            String description =
                    cleanHtml(
                            item.path("description").asText("")
                    );

            String url =
                    item.path("url").asText("");

            if (title.isBlank() || url.isBlank()) {
                continue;
            }

            List<String> languages =
                    normalizeArray(item.path("languages"));

            List<String> skills =
                    normalizeArray(
                            firstAvailable(
                                    item,
                                    "skillstags",
                                    "skillsTags"
                            )
                    );

            List<String> topics =
                    normalizeArray(item.path("topic"));

            List<String> industries =
                    normalizeArray(item.path("industry"));

            LearningResource resource =
                    new LearningResource(
                            title,
                            description,
                            url,
                            "",
                            "ibm",
                            "unknown",
                            "IBM SkillsBuild"
                    );

            indexedResources.add(
                    new IndexedResource(
                            resource,
                            normalize(title),
                            normalize(description),
                            languages,
                            skills,
                            topics,
                            industries
                    )
            );
        }

        cachedResources =
                List.copyOf(indexedResources);

        lastFetchTime = System.currentTimeMillis();

        long fetchDuration =
                (System.nanoTime() - fetchStart)
                        / 1_000_000;

        System.out.println(
                "IBM SkillsBuild catalogue loaded: "
                        + cachedResources.size()
                        + " resources"
        );

        System.out.println(
                "IBM SkillsBuild catalogue processing took "
                        + fetchDuration + " ms"
        );

        return cachedResources;
    }

    private int calculateScore(
            IndexedResource item,
            String normalizedQuery) {

        int score = 0;

        if (item.normalizedTitle()
                .contains(normalizedQuery)) {
            score += 10;
        }

        if (item.normalizedDescription()
                .contains(normalizedQuery)) {
            score += 3;
        }

        String[] keywords =
                normalizedQuery.split("\\s+");

        for (String keyword : keywords) {

            if (keyword.length() < 2) {
                continue;
            }

            if (item.normalizedTitle().contains(keyword)) {
                score += 4;
            }

            if (item.normalizedDescription().contains(keyword)) {
                score += 1;
            }

            if (containsKeyword(item.skills(), keyword)) {
                score += 3;
            }

            if (containsKeyword(item.topics(), keyword)) {
                score += 3;
            }

            if (containsKeyword(item.industries(), keyword)) {
                score += 2;
            }
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

        for (String language : item.languages()) {

            if (selectedLanguages.contains(language)) {
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

        if (node.isArray()) {

            for (JsonNode value : node) {

                String normalized =
                        normalize(value.asText());

                if (!normalized.isBlank()) {
                    values.add(normalized);
                }
            }

        } else if (node.isValueNode()) {

            String normalized =
                    normalize(node.asText());

            if (!normalized.isBlank()) {
                values.add(normalized);
            }
        }

        return List.copyOf(values);
    }

    private JsonNode firstAvailable(
            JsonNode item,
            String first,
            String second) {

        JsonNode firstNode = item.path(first);

        if (!firstNode.isMissingNode()
                && !firstNode.isNull()) {
            return firstNode;
        }

        return item.path(second);
    }

    private String normalizeLanguage(String language) {

        String normalized =
                language.trim().toLowerCase(Locale.ROOT);

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
            LearningResource resource,
            String normalizedTitle,
            String normalizedDescription,
            List<String> languages,
            List<String> skills,
            List<String> topics,
            List<String> industries
    ) {
    }

    private record Candidate(
            LearningResource resource,
            int score
    ) {
    }
}