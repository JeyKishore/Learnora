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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class CourseraProvider implements LearningResourceProvider {

    private static final String API_URL =
            "https://api.coursera.org/api/courses.v1";

    private static final String FIELDS =
            "name,slug,description,primaryLanguages,workload,photoUrl,courseType";

    private static final int PAGE_SIZE = 1000;

    private static final Duration CACHE_DURATION =
            Duration.ofHours(6);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    private final ExecutorService catalogueExecutor =
            Executors.newSingleThreadExecutor(r -> {
                Thread thread = new Thread(
                        r, "coursera-catalogue-loader");
                thread.setDaemon(true);
                return thread;
            });

    private final AtomicBoolean loading =
            new AtomicBoolean(false);

    private volatile List<JsonNode> cachedCourses =
            List.of();

    private volatile Instant lastFetched;

    public CourseraProvider() {

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        this.objectMapper = new ObjectMapper();
    }

    @Override
    public String getPlatform() {
        return "Coursera";
    }
    public String getCatalogueStatus() {

    if (!cachedCourses.isEmpty()) {
        return "READY";
    }

    if (loading.get()) {
        return "LOADING";
    }

    return "NOT_STARTED";
}
public int getCatalogueSize() {
    return cachedCourses.size();
}

    @Override
    public List<LearningResource> search(
            String query,
            String languages) {

        if (query == null || query.isBlank()) {
            return List.of();
        }

        // Start catalogue loading without blocking the search.
        ensureCatalogueLoading();

        // Immediately use the available cached catalogue.
        List<JsonNode> courses = cachedCourses;

        if (courses.isEmpty()) {
            System.out.println(
                    "Coursera catalogue is loading in background.");
            return List.of();
        }

        String searchQuery =
                query.toLowerCase(Locale.ROOT).trim();

        List<ScoredResource> results = new ArrayList<>();

        for (JsonNode course : courses) {

            if (!supportsLanguage(course, languages)) {
                continue;
            }

            int score = calculateScore(course, searchQuery);

            if (score <= 0) {
                continue;
            }

            String name =
                    course.path("name").asText("");

            String description =
                    course.path("description").asText("");

            String slug =
                    course.path("slug").asText("");

            if (slug.isBlank()) {
                continue;
            }

            String url =
                    "https://www.coursera.org/learn/" + slug;

            String thumbnail =
                    course.path("photoUrl").asText(null);

            String workload =
                    course.path("workload").asText("Not specified");

            LearningResource resource =
                    new LearningResource(
                            name,
                            description,
                            url,
                            thumbnail,
                            "Coursera",
                            "unknown",
                            "Coursera | " + workload
                    );

            results.add(new ScoredResource(resource, score));
        }

        Set<String> uniqueUrls = new HashSet<>();

        return results.stream()
                .sorted(Comparator.comparingInt(
                        ScoredResource::score).reversed())
                .map(ScoredResource::resource)
                .filter(resource ->
                        uniqueUrls.add(resource.getUrl()))
                .limit(20)
                .toList();
    }

    private void ensureCatalogueLoading() {

        boolean cacheExpired =
                lastFetched == null ||
                Instant.now().isAfter(
                        lastFetched.plus(CACHE_DURATION));

        if (!cacheExpired) {
            return;
        }

        if (!loading.compareAndSet(false, true)) {
            return;
        }

        catalogueExecutor.submit(() -> {

            try {
                loadCatalogue();

            } catch (Exception e) {

                System.err.println(
                        "Coursera background loading failed: "
                                + e.getMessage());

            } finally {
                loading.set(false);
            }
        });
    }

    private void loadCatalogue() throws Exception {

        List<JsonNode> allCourses = new ArrayList<>();

        int start = 0;
        int total = Integer.MAX_VALUE;

        while (start < total) {

            String url = API_URL
                    + "?fields=" + FIELDS
                    + "&limit=" + PAGE_SIZE
                    + "&start=" + start;

            HttpRequest request =
                    HttpRequest.newBuilder()
                            .uri(URI.create(url))
                            .timeout(Duration.ofSeconds(20))
                            .header("Accept", "application/json")
                            .GET()
                            .build();

            HttpResponse<String> response =
                    httpClient.send(
                            request,
                            HttpResponse.BodyHandlers.ofString()
                    );

            if (response.statusCode() != 200) {
                throw new IllegalStateException(
                        "Coursera API returned HTTP "
                                + response.statusCode());
            }

            JsonNode root =
                    objectMapper.readTree(response.body());

            JsonNode elements =
                    root.path("elements");

            if (!elements.isArray()) {
                throw new IllegalStateException(
                        "Invalid Coursera catalogue response");
            }

            if (start == 0) {
                total = root.path("paging")
                        .path("total")
                        .asInt(0);
            }

            if (elements.isEmpty()) {
                break;
            }

            elements.forEach(allCourses::add);

            JsonNode next =
                    root.path("paging").path("next");

            if (next.isMissingNode() || next.isNull()) {
                break;
            }

            int nextStart = next.asInt(-1);

            if (nextStart <= start) {
                break;
            }

            start = nextStart;
        }

        if (allCourses.isEmpty()) {
            throw new IllegalStateException(
                    "Coursera returned an empty catalogue");
        }

        // Publish the completed catalogue atomically.
        cachedCourses = List.copyOf(allCourses);
        lastFetched = Instant.now();

        System.out.println(
                "Coursera catalogue loaded: "
                        + cachedCourses.size()
                        + " courses");
    }

    private int calculateScore(
            JsonNode course,
            String query) {

        String name = course.path("name")
                .asText("")
                .toLowerCase(Locale.ROOT);

        String description = course.path("description")
                .asText("")
                .toLowerCase(Locale.ROOT);

        int score = 0;

        if (name.equals(query)) {
            score += 100;

        } else if (name.contains(query)) {
            score += 60;
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
        }

        return score;
    }

    private boolean supportsLanguage(
            JsonNode course,
            String languages) {

        if (languages == null || languages.isBlank()) {
            return true;
        }

        JsonNode courseLanguages =
                course.path("primaryLanguages");

        if (!courseLanguages.isArray()) {
            return false;
        }

        for (String selected : languages.split(",")) {

            String language = selected.trim();

            for (JsonNode available : courseLanguages) {

                if (available.asText()
                        .equalsIgnoreCase(language)) {
                    return true;
                }
            }
        }

        return false;
    }

    private record ScoredResource(
            LearningResource resource,
            int score) {
    }
}