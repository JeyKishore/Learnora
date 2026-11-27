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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Component
public class KaggleLearnProvider implements LearningResourceProvider {

    private static final String API_URL =
            "https://www.kaggle.com/api/i/education.EducationService/GetTracks";

    private static final String BASE_URL =
            "https://www.kaggle.com";

    private static final Duration CACHE_DURATION =
            Duration.ofHours(6);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    private volatile List<JsonNode> cachedTracks = List.of();
    private volatile Instant lastFetched;

    public KaggleLearnProvider() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();

        this.objectMapper = new ObjectMapper();
    }

    @Override
    public String getPlatform() {
        return "Kaggle";
    }

    @Override
    public List<LearningResource> search(
            String query,
            String languages) {

        if (!supportsEnglish(languages)) {
            return List.of();
        }

        try {
            List<JsonNode> tracks = getTracks();

            String searchQuery = query.toLowerCase(Locale.ROOT).trim();

            List<ScoredResource> results = new ArrayList<>();

            for (JsonNode track : tracks) {

                if (!track.path("visible").asBoolean(false)) {
                    continue;
                }

                int score = calculateScore(track, searchQuery);

                if (score <= 0) {
                    continue;
                }

                String name = track.path("name").asText("");
                String description =
                        track.path("description").asText("");

                String slug = track.path("trackSlug").asText("");

                if (slug.isBlank()) {
                    continue;
                }

                int hours =
                        track.path("estimatedTimeHours").asInt(0);

                int lessonCount =
                        track.path("lessons").isArray()
                                ? track.path("lessons").size()
                                : 0;

                String url = BASE_URL + "/learn/" + slug;

                String provider =
                        "Kaggle Learn | " + hours
                                + " hours | " + lessonCount + " lessons";

                LearningResource resource = new LearningResource(
                        name,
                        description,
                        url,
                        null,
                        "Kaggle",
                        "free",
                        provider
                );

                results.add(new ScoredResource(resource, score));
            }

            return results.stream()
                    .sorted(Comparator.comparingInt(
                            ScoredResource::score).reversed())
                    .limit(20)
                    .map(ScoredResource::resource)
                    .toList();

        } catch (Exception e) {
            System.err.println(
                    "Kaggle Learn search failed: " + e.getMessage());

            return List.of();
        }
    }

    private List<JsonNode> getTracks() throws Exception {

        if (lastFetched != null
                && Instant.now().isBefore(
                        lastFetched.plus(CACHE_DURATION))) {
            return cachedTracks;
        }

        synchronized (this) {

            if (lastFetched != null
                    && Instant.now().isBefore(
                            lastFetched.plus(CACHE_DURATION))) {
                return cachedTracks;
            }

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(API_URL))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .build();

            HttpResponse<String> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString()
            );

            if (response.statusCode() != 200) {
                throw new IllegalStateException(
                        "Kaggle API returned HTTP "
                                + response.statusCode());
            }

            JsonNode root = objectMapper.readTree(response.body());

            JsonNode tracksNode = root.path("tracks");

            if (!tracksNode.isArray()) {
                throw new IllegalStateException(
                        "Invalid Kaggle tracks response");
            }

            List<JsonNode> tracks = new ArrayList<>();

            tracksNode.forEach(tracks::add);

            cachedTracks = List.copyOf(tracks);
            lastFetched = Instant.now();

            return cachedTracks;
        }
    }

    private int calculateScore(JsonNode track, String query) {

        if (query.isBlank()) {
            return 0;
        }

        String name = track.path("name")
                .asText("")
                .toLowerCase(Locale.ROOT);

        String description = track.path("description")
                .asText("")
                .toLowerCase(Locale.ROOT);

        String slug = track.path("trackSlug")
                .asText("")
                .toLowerCase(Locale.ROOT);

        int score = 0;

        if (name.equals(query)) {
            score += 100;
        } else if (name.contains(query)) {
            score += 60;
        }

        if (slug.contains(query.replace(" ", "-"))) {
            score += 40;
        }

        if (description.contains(query)) {
            score += 25;
        }

        String[] keywords = query.split("\\s+");

        for (String keyword : keywords) {

            if (keyword.length() < 2) {
                continue;
            }

            if (name.contains(keyword)) {
                score += 20;
            }

            if (description.contains(keyword)) {
                score += 5;
            }

            JsonNode tags = track.path("tags");

            if (tags.isArray()) {
                for (JsonNode tag : tags) {

                    String tagName = tag.path("name")
                            .asText("")
                            .toLowerCase(Locale.ROOT);

                    if (tagName.contains(keyword)) {
                        score += 15;
                    }
                }
            }
        }

        return score;
    }

    private boolean supportsEnglish(String languages) {

        if (languages == null || languages.isBlank()) {
            return true;
        }

        return List.of(languages.split(","))
                .stream()
                .map(String::trim)
                .anyMatch("en"::equalsIgnoreCase);
    }

    private record ScoredResource(
            LearningResource resource,
            int score) {
    }
}
