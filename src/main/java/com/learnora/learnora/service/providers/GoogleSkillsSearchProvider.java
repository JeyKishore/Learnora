package com.learnora.learnora.service.providers;

import com.learnora.learnora.model.LearningResource;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Component
public class GoogleSkillsSearchProvider implements LearningResourceProvider {

    @Override
    public String getPlatform() {
        return "Google Skills";
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
                "https://www.skills.google/catalog?keywords="
                        + encodedQuery;

        LearningResource resource = new LearningResource(
                "Explore " + query + " learning resources on Google Skills",
                "Search Google Skills for courses, learning paths "
                        + "and hands-on labs related to " + query
                        + ". Sign-in may be required.",
                url,
                null,
                "Google Skills",
                "unknown",
                "External search | Not a verified course listing"
        );

        return List.of(resource);
    }
}