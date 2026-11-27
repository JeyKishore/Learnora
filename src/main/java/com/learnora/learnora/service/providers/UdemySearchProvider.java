package com.learnora.learnora.service.providers;

import com.learnora.learnora.model.LearningResource;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Component
public class UdemySearchProvider implements LearningResourceProvider {

    @Override
    public String getPlatform() {
        return "Udemy";
    }

    @Override
    public List<LearningResource> search(
            String query, String languages) {

        if (query == null || query.isBlank()) {
            return List.of();
        }

        String encodedQuery = URLEncoder.encode(
                query.trim(),
                StandardCharsets.UTF_8
        );

        String url =
                "https://www.udemy.com/courses/search/?q="
                        + encodedQuery;

        LearningResource resource = new LearningResource(
                "Explore " + query + " courses on Udemy",
                "Search Udemy for courses related to "
                        + query + ". Course availability, pricing "
                        + "and language are determined by Udemy.",
                url,
                null,
                "Udemy",
                "unknown",
                "External search | Not a verified course listing"
        );

        return List.of(resource);
    }
}