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

    private JsonNode cachedCatalog;
    private long lastFetchTime;

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

            JsonNode catalog = getCatalog();

            List<String> selectedLanguages =
                    parseLanguages(languages);

            List<Candidate> candidates =
                    new ArrayList<>();

            Set<String> addedUrls =
                    new HashSet<>();

            processResources(
                    catalog.path("courses"),
                    query,
                    selectedLanguages,
                    candidates,
                    addedUrls
            );

            processResources(
                    catalog.path("learningPaths"),
                    query,
                    selectedLanguages,
                    candidates,
                    addedUrls
            );

            processResources(
                    catalog.path("modules"),
                    query,
                    selectedLanguages,
                    candidates,
                    addedUrls
            );

            return candidates.stream()
                    .sorted(Comparator.comparingInt(
                            Candidate::score).reversed())
                    .limit(20)
                    .map(Candidate::resource)
                    .toList();

        } catch (Exception e) {

            throw new IllegalStateException(
                    "Unable to retrieve Microsoft Learn resources: "
                            + e.getMessage(), e
            );
        }
    }

    private synchronized JsonNode getCatalog()
            throws Exception {

        long currentTime = System.currentTimeMillis();

        if (cachedCatalog != null
                && currentTime - lastFetchTime < CACHE_DURATION_MS) {

            return cachedCatalog;
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(CATALOG_URL))
                .timeout(Duration.ofSeconds(45))
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
                    "Microsoft catalogue returned HTTP "
                            + response.statusCode()
            );
        }

        cachedCatalog = objectMapper.readTree(response.body());
        lastFetchTime = currentTime;

        return cachedCatalog;
    }

    private void processResources(
            JsonNode resources,
            String query,
            List<String> selectedLanguages,
            List<Candidate> candidates,
            Set<String> addedUrls) {

        if (!resources.isArray()) {
            return;
        }

        for (JsonNode item : resources) {

            String title = item.path("title").asText("");
            String summary = cleanHtml(
                    item.path("summary").asText("")
            );

            String url = item.path("url").asText("");

            if (title.isBlank() || url.isBlank()) {
                continue;
            }

            if (!matchesLanguage(item, selectedLanguages)) {
                continue;
            }

            int score = calculateScore(item, query);

            if (score < 4) {
                continue;
            }

            if (!addedUrls.add(url)) {
                continue;
            }

            String type = item.path("type").asText("resource");

            String duration = getDuration(item);

            String level = getLevel(item);

            String language = getLanguage(item);

            String provider = "Microsoft Learn"
                    + " · " + formatType(type)
                    + " · " + level
                    + " · " + duration
                    + " · " + language;

            String thumbnail = item.path("icon_url").asText("");

            LearningResource resource = new LearningResource(
                    title,
                    summary,
                    url,
                    thumbnail,
                    "microsoft",
                    "unknown",
                    provider
            );

            candidates.add(new Candidate(resource, score));
        }
    }

    private int calculateScore(JsonNode item, String query) {

        String normalizedQuery = normalize(query);

        String title = normalize(
                item.path("title").asText("")
        );

        String summary = normalize(
                item.path("summary").asText("")
        );

        int score = 0;

        if (title.contains(normalizedQuery)) {
            score += 20;
        }

        if (summary.contains(normalizedQuery)) {
            score += 8;
        }

        String[] keywords = normalizedQuery.split("\\s+");

        int matchedKeywords = 0;

        for (String keyword : keywords) {

            if (keyword.length() < 2) {
                continue;
            }

            boolean matched = false;

            if (title.contains(keyword)) {
                score += 6;
                matched = true;
            }

            if (summary.contains(keyword)) {
                score += 2;
                matched = true;
            }

            if (containsValue(item.path("subjects"), keyword)) {
                score += 5;
                matched = true;
            }

            if (containsValue(item.path("products"), keyword)) {
                score += 4;
                matched = true;
            }

            if (containsValue(item.path("roles"), keyword)) {
                score += 2;
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

    private boolean containsValue(
            JsonNode node,
            String keyword) {

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

    private boolean matchesLanguage(
            JsonNode item,
            List<String> selectedLanguages) {

        if (selectedLanguages.isEmpty()) {
            return true;
        }

        JsonNode locales = item.path("locales");

        if (!locales.isArray()) {
            locales = item.path("locale").isMissingNode()
                    ? null
                    : item.path("locale");
        }

        if (locales == null) {
            return false;
        }

        if (locales.isArray()) {

            for (JsonNode locale : locales) {

                String available =
                        normalizeLanguage(locale.asText());

                if (selectedLanguages.contains(available)) {
                    return true;
                }
            }

        } else {

            String available =
                    normalizeLanguage(locales.asText());

            return selectedLanguages.contains(available);
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

            double hours = item.path("duration_in_hours").asDouble();

            return hours + " hours";
        }

        if (item.has("duration_in_minutes")) {

            int minutes =
                    item.path("duration_in_minutes").asInt();

            if (minutes >= 60) {

                double hours = minutes / 60.0;

                return String.format(
                        Locale.ROOT,
                        "%.1f hours",
                        hours
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

        return item.path("locale").asText("Unknown language");
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

    private record Candidate(
            LearningResource resource,
            int score) {
    }
}
