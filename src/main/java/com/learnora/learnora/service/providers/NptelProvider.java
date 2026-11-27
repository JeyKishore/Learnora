package com.learnora.learnora.service.providers;

import com.learnora.learnora.model.LearningResource;

import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class NptelProvider implements LearningResourceProvider {

    private static final String NPTEL_DATA_URL =
        "https://nptel.ac.in/courses/__data.json";
    private final ObjectMapper objectMapper;

    private final HttpClient httpClient;

    private volatile List<NptelCourse> cachedCourses =
            new ArrayList<>();

    private volatile long lastFetchTime = 0;

    private static final long CACHE_DURATION =
            6 * 60 * 60 * 1000L;

    public NptelProvider(ObjectMapper objectMapper) {

        this.objectMapper = objectMapper;

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    @Override
    public String getPlatform() {
        return "nptel";
    }

    @Override
    public List<LearningResource> search(
            String query,
            String languages) {

        if (query == null || query.isBlank()) {
            return List.of();
        }

        try {

            List<NptelCourse> courses = getCourses();

            String normalizedQuery = normalize(query);

            List<ScoredCourse> matches = new ArrayList<>();

            for (NptelCourse course : courses) {

                String title = normalize(course.title());

                String institute = normalize(course.institute());

                String professor = normalize(course.professor());

                int score = calculateScore(
                        normalizedQuery,
                        title,
                        institute,
                        professor
                );

                if (score > 0) {
                    matches.add(new ScoredCourse(course, score));
                }
            }

            matches.sort(
                    Comparator.comparingInt(
                            ScoredCourse::score
                    ).reversed()
            );

            List<LearningResource> results = new ArrayList<>();

            for (ScoredCourse match : matches) {

                NptelCourse course = match.course();

                String description =
                        "Institute: " + course.institute()
                        + " | Professor: " + course.professor()
                        + " | Content type: " + course.contentType()
                        + " | Learning: Free. Optional certification "
                        + "examination may require payment.";

                String courseUrl =
                        "https://nptel.ac.in/courses/"
                        + course.id() + "/";

                results.add(
                        new LearningResource(
                                course.title(),
                                description,
                                courseUrl,
                                "",
                                "nptel",
                                "free",
                                "NPTEL · " + course.institute()
                        )
                );
            }

            return results;

        } catch (Exception e) {

            throw new IllegalStateException(
                    "Unable to retrieve NPTEL courses: "
                            + e.getMessage(),
                    e
            );
        }
    }

    // FETCH AND CACHE NPTEL CATALOGUE

    private List<NptelCourse> getCourses()
            throws IOException, InterruptedException {

        long currentTime = System.currentTimeMillis();

        if (!cachedCourses.isEmpty()
                && currentTime - lastFetchTime < CACHE_DURATION) {

            return cachedCourses;
        }

        synchronized (this) {

            currentTime = System.currentTimeMillis();

            if (!cachedCourses.isEmpty()
                    && currentTime - lastFetchTime < CACHE_DURATION) {

                return cachedCourses;
            }

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(NPTEL_DATA_URL))
                    .timeout(Duration.ofSeconds(30))
                    .header("User-Agent", "Learnora/1.0")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString()
            );

            if (response.statusCode() != 200) {
                throw new IOException(
                        "NPTEL returned HTTP "
                                + response.statusCode()
                );
            }

            JsonNode root = objectMapper.readTree(response.body());

            List<NptelCourse> courses =
                    parseCourses(root);

            if (courses.isEmpty()) {
                throw new IOException(
                        "No courses found in NPTEL catalogue."
                );
            }

            cachedCourses = List.copyOf(courses);

            lastFetchTime = System.currentTimeMillis();

            return cachedCourses;
        }
    }

    // PARSE SVELTEKIT SERIALIZED DATA

    private List<NptelCourse> parseCourses(JsonNode root) {

        JsonNode nodes = root.path("nodes");

        if (!nodes.isArray() || nodes.size() < 2) {
            throw new IllegalStateException(
                    "Unexpected NPTEL catalogue format."
            );
        }

        JsonNode data = nodes.get(1).path("data");

        if (!data.isArray() || data.isEmpty()) {
            throw new IllegalStateException(
                    "NPTEL course data is missing."
            );
        }

        JsonNode rootObject = data.get(0);

        int coursesReference =
                rootObject.path("courses").asInt(-1);

        if (coursesReference < 0
                || coursesReference >= data.size()) {

            throw new IllegalStateException(
                    "NPTEL course reference is invalid."
            );
        }

        JsonNode courseReferences = data.get(coursesReference);

        List<NptelCourse> courses = new ArrayList<>();

        for (JsonNode reference : courseReferences) {

            int objectIndex = reference.asInt(-1);

            if (objectIndex < 0 || objectIndex >= data.size()) {
                continue;
            }

            JsonNode courseObject = data.get(objectIndex);

            if (!courseObject.isObject()) {
                continue;
            }

            String id = resolveValue(
                    data, courseObject, "id"
            );

            String title = resolveValue(
                    data, courseObject, "title"
            );

            String institute = resolveValue(
                    data, courseObject, "instituteName"
            );

            String professor = resolveValue(
                    data, courseObject, "professor"
            );

            String contentType = resolveValue(
                    data, courseObject, "contentType"
            );

            if (id.isBlank() || title.isBlank()) {
                continue;
            }

            courses.add(
                    new NptelCourse(
                            id,
                            title,
                            institute,
                            professor,
                            contentType
                    )
            );
        }

        return courses;
    }

    // RESOLVE INDEXED VALUES

    private String resolveValue(
            JsonNode data,
            JsonNode course,
            String field) {

        JsonNode reference = course.get(field);

        if (reference == null || !reference.canConvertToInt()) {
            return "";
        }

        int index = reference.asInt(-1);

        if (index < 0 || index >= data.size()) {
            return "";
        }

        JsonNode value = data.get(index);

        if (value == null || value.isNull()) {
            return "";
        }

        return value.asText("");
    }

    // RELEVANCE SCORING

    private int calculateScore(
            String query,
            String title,
            String institute,
            String professor) {

        String[] words = query.split("\\s+");

        int matched = 0;
        int score = 0;

        for (String word : words) {

            if (word.length() < 2) {
                continue;
            }

            if (title.contains(word)) {
                matched++;
                score += 5;

            } else if (institute.contains(word)) {
                matched++;
                score += 2;

            } else if (professor.contains(word)) {
                matched++;
                score += 1;
            }
        }

        if (matched == 0) {
            return 0;
        }

        if (words.length > 1
                && matched < Math.ceil(words.length / 2.0)) {
            return 0;
        }

        if (title.contains(query)) {
            score += 10;
        }

        return score;
    }

    // NORMALIZE SEARCH TEXT

    private String normalize(String text) {

        if (text == null) {
            return "";
        }

        return text.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .trim()
                .replaceAll("\\s+", " ");
    }

    // COURSE DATA MODEL

    private record NptelCourse(
            String id,
            String title,
            String institute,
            String professor,
            String contentType
    ) {}

    private record ScoredCourse(
            NptelCourse course,
            int score
    ) {}
}