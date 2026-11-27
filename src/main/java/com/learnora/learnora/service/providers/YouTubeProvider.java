
package com.learnora.learnora.service.providers;

import com.learnora.learnora.model.LearningResource;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class YouTubeProvider implements LearningResourceProvider {

    @Value("${youtube.api.key:}")
    private String youtubeApiKey;

    private final ObjectMapper objectMapper;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    private static final Set<String> SUPPORTED_LANGUAGES = Set.of(
            "en", "ta", "hi", "te", "ml",
            "kn", "fr", "de", "es", "ja"
    );

    public YouTubeProvider(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String getPlatform() {
        return "youtube";
    }

    @Override
    public List<LearningResource> search(
            String query,
            String languages) {

        if (query == null || query.isBlank()) {
            return new ArrayList<>();
        }

        if (youtubeApiKey == null || youtubeApiKey.isBlank()) {
            throw new IllegalStateException(
                    "YouTube API key is missing. Configure YOUTUBE_API_KEY."
            );
        }

        List<String> languageCodes = new ArrayList<>();

        if (languages != null && !languages.isBlank()) {
            for (String language : languages.split(",")) {
                String code = language.trim().toLowerCase();

                if (SUPPORTED_LANGUAGES.contains(code)
                        && !languageCodes.contains(code)) {
                    languageCodes.add(code);
                }
            }
        }

        if (languageCodes.isEmpty()) {
            languageCodes.add("");
        }

        Map<String, LearningResource> uniqueResources =
                new LinkedHashMap<>();

        Map<String, Integer> relevanceScores =
                new LinkedHashMap<>();

        try {
            for (String languageCode : languageCodes) {

                List<LearningResource> results =
                        fetchYouTubeResources(query, languageCode);

                for (LearningResource resource : results) {

                    String url = resource.getUrl();

                    if (!uniqueResources.containsKey(url)) {
                        uniqueResources.put(url, resource);

                        relevanceScores.put(
                                url,
                                getRelevanceScore(
                                        query,
                                        resource.getTitle(),
                                        resource.getDescription()
                                )
                        );
                    }
                }
            }

        } catch (InterruptedException e) {

            Thread.currentThread().interrupt();

            throw new IllegalStateException(
                    "YouTube search was interrupted.", e
            );

        } catch (IOException e) {

            throw new IllegalStateException(
                    "Unable to process YouTube search results.", e
            );
        }

        List<LearningResource> finalResults =
                new ArrayList<>(uniqueResources.values());

        finalResults.sort(
                Comparator.comparingInt(
                        (LearningResource resource) ->
                                relevanceScores.getOrDefault(
                                        resource.getUrl(), 0
                                )
                ).reversed()
        );

        return finalResults;
    }

    // SEARCH YOUTUBE

    private List<LearningResource> fetchYouTubeResources(
            String query,
            String languageCode)
            throws IOException, InterruptedException {

        List<LearningResource> resources = new ArrayList<>();

        String searchQuery = query.trim();

        switch (languageCode) {
            case "ta" -> searchQuery += " தமிழ்";
            case "hi" -> searchQuery += " हिंदी";
            case "te" -> searchQuery += " తెలుగు";
            case "ml" -> searchQuery += " മലയാളം";
            case "kn" -> searchQuery += " ಕನ್ನಡ";
        }

        String encodedQuery =
                URLEncoder.encode(searchQuery, StandardCharsets.UTF_8);

        String apiUrl =
                "https://www.googleapis.com/youtube/v3/search"
                + "?part=snippet"
                + "&q=" + encodedQuery
                + "&type=video"
                + "&maxResults=50"
                + "&safeSearch=strict";

        if (Set.of("ta", "hi", "te", "ml", "kn")
                .contains(languageCode)) {
            apiUrl += "&regionCode=IN";
        }

        if (!languageCode.isBlank()) {
            apiUrl += "&relevanceLanguage=" + languageCode;
        }

        apiUrl += "&key=" + youtubeApiKey;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(
                request,
                HttpResponse.BodyHandlers.ofString()
        );

        if (response.statusCode() != 200) {
            throw new IllegalStateException(
                    "YouTube Search API returned HTTP "
                    + response.statusCode()
            );
        }

        JsonNode root = objectMapper.readTree(response.body());
        JsonNode items = root.path("items");

        List<String> videoIds = new ArrayList<>();

        Map<String, JsonNode> videoSnippets =
                new LinkedHashMap<>();

        for (JsonNode item : items) {

            JsonNode videoId = item.path("id").path("videoId");

            if (videoId.isMissingNode() || videoId.isNull()) {
                continue;
            }

            String id = videoId.asText();

            videoIds.add(id);
            videoSnippets.put(id, item.path("snippet"));
        }

        if (videoIds.isEmpty()) {
            return resources;
        }

        Map<String, String> audioLanguages =
                new LinkedHashMap<>();

        if (!languageCode.isBlank()) {
            audioLanguages = fetchAudioLanguages(videoIds);
        }

        for (String id : videoIds) {

            String languageStatus = "";

            if (!languageCode.isBlank()) {

                String audioLanguage = audioLanguages.get(id);

                if (audioLanguage != null && !audioLanguage.isBlank()) {

                    if (!audioLanguage.toLowerCase(Locale.ROOT)
                            .startsWith(languageCode)) {
                        continue;
                    }

                    languageStatus = "Language verified";

                } else {
                    languageStatus = "Language unverified";
                }
            }

            JsonNode snippet = videoSnippets.get(id);

            String title = snippet.path("title")
                    .asText("YouTube Learning Video");

            String description = snippet.path("description")
                    .asText("Watch this learning video on YouTube.");

            int relevanceScore =
                    getRelevanceScore(query, title, description);

            if (relevanceScore == 0) {
                continue;
            }

            String channel = snippet.path("channelTitle")
                    .asText("YouTube");

            String thumbnail = snippet.path("thumbnails")
                    .path("high")
                    .path("url")
                    .asText("");

            if (thumbnail.isBlank()) {
                thumbnail = snippet.path("thumbnails")
                        .path("default")
                        .path("url")
                        .asText("");
            }

            String videoUrl =
                    "https://www.youtube.com/watch?v=" + id;

            String provider = channel;

            if (!languageStatus.isBlank()) {
                provider += " · " + languageStatus;
            }

            resources.add(
                    new LearningResource(
                            title,
                            description,
                            videoUrl,
                            thumbnail,
                            "youtube",
                            "unknown",
                            provider
                    )
            );
        }

        return resources;
    }

    // TOPIC RELEVANCE SCORING

    private int getRelevanceScore(
            String query,
            String title,
            String description) {

        String normalizedQuery = normalizeText(query);
        String normalizedTitle = normalizeText(title);
        String normalizedDescription = normalizeText(description);

        if (normalizedQuery.isBlank()) {
            return 0;
        }

        Set<String> queryWords =
                new LinkedHashSet<>(
                        List.of(normalizedQuery.split("\\s+"))
                );

        int matchedWords = 0;
        int titleMatches = 0;
        int descriptionMatches = 0;

        for (String word : queryWords) {

            boolean foundInTitle = normalizedTitle.contains(word);

            boolean foundInDescription =
                    normalizedDescription.contains(word);

            if (foundInTitle) {
                titleMatches++;
                matchedWords++;
            } else if (foundInDescription) {
                descriptionMatches++;
                matchedWords++;
            }
        }

        int totalWords = queryWords.size();

        if (matchedWords == 0) {
            return 0;
        }

        if (totalWords == 1) {
            return (titleMatches * 5) + descriptionMatches;
        }

        int minimumMatches =
                (int) Math.ceil(totalWords / 2.0);

        if (matchedWords < minimumMatches || titleMatches == 0) {
            return 0;
        }

        int score = (titleMatches * 5) + descriptionMatches;

        if (normalizedTitle.contains(normalizedQuery)) {
            score += 10;
        }

        return score;
    }

    // NORMALIZE TEXT

    private String normalizeText(String text) {

        if (text == null) {
            return "";
        }

        return text.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .trim()
                .replaceAll("\\s+", " ");
    }

    // FETCH VIDEO LANGUAGE METADATA

    private Map<String, String> fetchAudioLanguages(
            List<String> videoIds)
            throws IOException, InterruptedException {

        Map<String, String> audioLanguages =
                new LinkedHashMap<>();

        for (int start = 0; start < videoIds.size(); start += 50) {

            List<String> batch = videoIds.subList(
                    start,
                    Math.min(start + 50, videoIds.size())
            );

            String ids = String.join(",", batch);

            String apiUrl =
                    "https://www.googleapis.com/youtube/v3/videos"
                    + "?part=snippet"
                    + "&id=" + ids
                    + "&key=" + youtubeApiKey;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString()
            );

            if (response.statusCode() != 200) {
                throw new IllegalStateException(
                        "YouTube Video Details API returned HTTP "
                        + response.statusCode()
                );
            }

            JsonNode root = objectMapper.readTree(response.body());

            for (JsonNode video : root.path("items")) {

                String id = video.path("id").asText("");

                String audioLanguage = video.path("snippet")
                        .path("defaultAudioLanguage")
                        .asText("");

                if (!id.isBlank() && !audioLanguage.isBlank()) {
                    audioLanguages.put(id, audioLanguage);
                }
            }
        }

        return audioLanguages;
    }
}
