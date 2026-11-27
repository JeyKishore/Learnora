package com.learnora.learnora.controller;

import com.learnora.learnora.model.LearningResource;
import com.learnora.learnora.service.ResourceSearchService;
import com.learnora.learnora.service.providers.CourseraProvider;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/resources")
public class ResourceController {

    private final ResourceSearchService resourceSearchService;
    private final CourseraProvider courseraProvider;

    public ResourceController(
            ResourceSearchService resourceSearchService,
            CourseraProvider courseraProvider) {

        this.resourceSearchService = resourceSearchService;
        this.courseraProvider = courseraProvider;
    }

    @GetMapping("/search")
    public List<LearningResource> searchResources(

            @RequestParam String query,

            @RequestParam(defaultValue = "all") String price,

            @RequestParam(defaultValue = "all") String platform,

            @RequestParam(required = false) String languages

    ) {

        return resourceSearchService.searchResources(
                query,
                price,
                platform,
                languages
        );
    }

    @GetMapping("/coursera-status")
    public Map<String, Object> getCourseraStatus() {

        return Map.of(
                "status", courseraProvider.getCatalogueStatus(),
                "size", courseraProvider.getCatalogueSize()
        );
    }
}