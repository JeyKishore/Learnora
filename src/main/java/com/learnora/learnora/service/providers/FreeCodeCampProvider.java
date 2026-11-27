package com.learnora.learnora.service.providers;

import com.learnora.learnora.model.LearningResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class FreeCodeCampProvider implements LearningResourceProvider {

    private static final String API_URL =
            "https://curriculum-db.freecodecamp.org/graphql";

    private static final String GRAPHQL_QUERY =
            """
            {
                superblocks {
                    name
                    dashedName
                    isCertification
                    blocks
                }
            }
            """;

    private static final long CACHE_DURATION_MS =
            6 * 60 * 60 * 1000L;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    private JsonNode cachedCatalog;
    private long lastFetchTime;

    public FreeCodeCampProvider() {

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();

        this.objectMapper = new ObjectMapper();
    }

    @Override
    public String getPlatform() {
        return "freecodecamp";
    }

    @Override
    public List<LearningResource> search(
            String query,
            String languages) {

        if (query == null || query.isBlank()) {
            return List.of();
        }

        try {

            JsonNode catalog = getCatalog();

            List<String> selectedLanguages =
                    parseLanguages(languages);

            // The catalogue does not expose language metadata.
            // Its curriculum pages are English-language resources.
            if (!selectedLanguages.isEmpty()
                    && !selectedLanguages.contains("en")) {
                return List.of();
            }

            JsonNode superblocks =
                    catalog.path("data").path("superblocks");

            if (!superblocks.isArray()) {
                return List.of();
            }

            List<Candidate> results = new ArrayList<>();

            Set<String> addedUrls = new HashSet<>();

            for (JsonNode item : superblocks) {

                String title =
                        item.path("name").asText("");

                String dashedName =
                        item.path("dashedName").asText("");

                if (title.isBlank() || dashedName.isBlank()) {
                    continue;
                }

                int score = calculateScore(query, title, dashedName);

                if (score < 4) {
                    continue;
                }

                String url =
                        "https://www.freecodecamp.org/learn/"
                                + dashedName + "/";

                if (!addedUrls.add(url)) {
                    continue;
                }

                boolean certification =
                        item.path("isCertification").asBoolean(false);

                JsonNode blocks = item.path("blocks");

                int blockCount =
                        blocks.isArray() ? blocks.size() : 0;

                String description =
                        "Official freeCodeCamp curriculum. "
                                + blockCount
                                + " learning blocks available. "
                                + "Open the curriculum to explore its "
                                + "lessons and projects.";

                String provider =
                        certification
                                ? "freeCodeCamp · Certification curriculum"
                                : "freeCodeCamp · Learning curriculum";

                LearningResource resource = new LearningResource(
                        title,
                        description,
                        url,
                        "",
                        "freecodecamp",
                        "unknown",
                        provider
                );

                results.add(new Candidate(resource, score));
            }

            return results.stream()
                    .sorted(
                            Comparator.comparingInt(
                                    Candidate::score
                            ).reversed()
                    )
                    .limit(20)
                    .map(Candidate::resource)
                    .toList();

        } catch (Exception e) {

            throw new IllegalStateException(
                    "Unable to retrieve freeCodeCamp resources: "
                            + e.getMessage(),
                    e
            );
        }
    }

    private synchronized JsonNode getCatalog()
            throws Exception {

        long currentTime = System.currentTimeMillis();

        if (cachedCatalog != null
                && currentTime - lastFetchTime
                < CACHE_DURATION_MS) {

            return cachedCatalog;
        }

        String requestBody = objectMapper.writeValueAsString(
                Map.of("query", GRAPHQL_QUERY)
        );

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_URL))
                .timeout(Duration.ofSeconds(45))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("User-Agent", "Learnora/1.0")
                .POST(
                        HttpRequest.BodyPublishers.ofString(
                                requestBody,
                                StandardCharsets.UTF_8
                        )
                )
                .build();

        HttpResponse<String> response = httpClient.send(
                request,
                HttpResponse.BodyHandlers.ofString()
        );

        if (response.statusCode() != 200) {

            throw new IllegalStateException(
                    "freeCodeCamp API returned HTTP "
                            + response.statusCode()
            );
        }

        JsonNode root = objectMapper.readTree(response.body());

        if (root.has("errors")) {

            throw new IllegalStateException(
                    "freeCodeCamp GraphQL returned errors: "
                            + root.path("errors").toString()
            );
        }

        cachedCatalog = root;
        lastFetchTime = currentTime;

        return cachedCatalog;
    }

    private int calculateScore(
            String query,
            String title,
            String dashedName) {

        String normalizedQuery = normalize(query);

        String normalizedTitle = normalize(title);

        String normalizedSlug = normalize(
                dashedName.replace("-", " ")
        );

        int score = 0;

        if (normalizedTitle.equals(normalizedQuery)) {
            score += 20;
        }

        if (normalizedTitle.contains(normalizedQuery)) {
            score += 12;
        }

        if (normalizedSlug.contains(normalizedQuery)) {
            score += 8;
        }

        String[] keywords = normalizedQuery.split("\\s+");

        int matchedKeywords = 0;

        for (String keyword : keywords) {

            if (keyword.length() < 2) {
                continue;
            }

            boolean matched = false;

            if (normalizedTitle.contains(keyword)) {
                score += 5;
                matched = true;
            }

            if (normalizedSlug.contains(keyword)) {
                score += 3;
                matched = true;
            }

            if (matched) {
                matchedKeywords++;
            }
        }

        if (keywords.length > 1 && matchedKeywords == 0) {
            return 0;
        }

        return score;
    }

    private List<String> parseLanguages(String languages) {

        if (languages == null || languages.isBlank()) {
            return List.of();
        }

        return List.of(languages.split(","))
                .stream()
                .map(language ->
                        language.trim().toLowerCase(Locale.ROOT))
                .toList();
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

    private record Candidate(
            LearningResource resource,
            int score) {
    }
}