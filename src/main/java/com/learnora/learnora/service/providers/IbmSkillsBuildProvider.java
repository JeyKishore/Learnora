package com.learnora.learnora.service.providers;

import com.learnora.learnora.model.LearningResource;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Component
public class IbmSkillsBuildProvider implements LearningResourceProvider {

    private static final String API_URL =
            "https://skillsbuild.org/api/learning-catalog?audience=adult-learner&lang=en-us";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

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
    public List<LearningResource> search(String query, String languages) {

        if (query == null || query.isBlank()) {
            return List.of();
        }

        try {

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(API_URL))
                    .timeout(Duration.ofSeconds(30))
                    .header("Accept", "application/json")
                    .header("User-Agent", "Learnora/1.0")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString()
            );

            if (response.statusCode() != 200) {
                throw new IllegalStateException(
                        "IBM catalog request failed: "
                                + response.statusCode()
                );
            }

            JsonNode root = objectMapper.readTree(response.body());

            JsonNode items = root.path("items");

            if (!items.isArray()) {
                return List.of();
            }

            List<LearningResource> results = new ArrayList<>();

            List<String> selectedLanguages =
                    parseLanguages(languages);

            for (JsonNode item : items) {

                String title = item.path("title").asText("");
                String description =
                        cleanHtml(item.path("description").asText(""));

                String url = item.path("url").asText("");

                if (title.isBlank() || url.isBlank()) {
                    continue;
                }

                // Apply language filtering.
                if (!matchesLanguage(item, selectedLanguages)) {
                    continue;
                }

                int score = calculateScore(
                        query,
                        title,
                        description,
                        item
                );

                // Ignore unrelated resources.
                if (score <= 0) {
                    continue;
                }

                LearningResource resource = new LearningResource(
                        title,
                        description,
                        url,
                        "",
                        "ibm",
                        "unknown",
                        "IBM SkillsBuild"
                );

                results.add(resource);
            }

            return results.stream()
                    .sorted(Comparator.comparingInt(
                            resource -> -calculateScore(
                                    query,
                                    resource.getTitle(),
                                    resource.getDescription(),
                                    null
                            )
                    ))
                    .limit(20)
                    .toList();

        } catch (Exception e) {

            throw new IllegalStateException(
                    "Unable to retrieve IBM SkillsBuild resources: "
                            + e.getMessage(),
                    e
            );
        }
    }

    private int calculateScore(
            String query,
            String title,
            String description,
            JsonNode item
    ) {

        String normalizedQuery = normalize(query);
        String normalizedTitle = normalize(title);
        String normalizedDescription = normalize(description);

        int score = 0;

        if (normalizedTitle.contains(normalizedQuery)) {
            score += 10;
        }

        if (normalizedDescription.contains(normalizedQuery)) {
            score += 3;
        }

        String[] keywords = normalizedQuery.split("\\s+");

        for (String keyword : keywords) {

            if (keyword.length() < 2) {
                continue;
            }

            if (normalizedTitle.contains(keyword)) {
                score += 4;
            }

            if (normalizedDescription.contains(keyword)) {
                score += 1;
            }

            if (item != null) {

                if (containsValue(item.path("skillstags"), keyword)) {
                    score += 3;
                }

                if (containsValue(item.path("topic"), keyword)) {
                    score += 3;
                }

                if (containsValue(item.path("industry"), keyword)) {
                    score += 2;
                }
            }
        }

        return score;
    }

    private boolean containsValue(JsonNode node, String keyword) {

        if (!node.isArray()) {
            return false;
        }

        for (JsonNode value : node) {

            if (normalize(value.asText()).contains(keyword)) {
                return true;
            }
        }

        return false;
    }

    private List<String> parseLanguages(String languages) {

        if (languages == null || languages.isBlank()) {
            return List.of();
        }

        return List.of(languages.split(","))
                .stream()
                .map(this::normalizeLanguage)
                .toList();
    }

    private boolean matchesLanguage(
            JsonNode item,
            List<String> selectedLanguages
    ) {

        if (selectedLanguages.isEmpty()) {
            return true;
        }

        JsonNode availableLanguages = item.path("languages");

        if (!availableLanguages.isArray()) {
            return false;
        }

        for (JsonNode language : availableLanguages) {

            String available = normalizeLanguage(language.asText());

            if (selectedLanguages.contains(available)) {
                return true;
            }
        }

        return false;
    }

    private String normalizeLanguage(String language) {

        String normalized = language.trim().toLowerCase(Locale.ROOT);

        if (normalized.startsWith("en-")) {
            return "en";
        }

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
}