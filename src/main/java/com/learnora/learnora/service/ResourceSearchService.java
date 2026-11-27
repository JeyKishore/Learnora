package com.learnora.learnora.service;

import com.learnora.learnora.model.LearningResource;
import com.learnora.learnora.service.providers.LearningResourceProvider;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Service
public class ResourceSearchService {

    private final List<LearningResourceProvider> providers;

    private final ExecutorService searchExecutor =
            Executors.newFixedThreadPool(9);

    public ResourceSearchService(
            List<LearningResourceProvider> providers) {
        this.providers = providers;
    }

    public List<LearningResource> searchResources(
            String query,
            String price,
            String platform,
            String languages) {

        if (query == null || query.isBlank()) {
            return new ArrayList<>();
        }

        String selectedPlatform =
                platform == null
                        ? "all"
                        : platform.trim().toLowerCase();

        long overallStart = System.nanoTime();

        List<CompletableFuture<List<LearningResource>>> tasks =
                new ArrayList<>();

        for (LearningResourceProvider provider : providers) {

            if (!selectedPlatform.equals("all")
                    && !selectedPlatform.equals(
                            provider.getPlatform().toLowerCase())) {
                continue;
            }

            CompletableFuture<List<LearningResource>> task =
                    CompletableFuture.supplyAsync(() -> {

                        long start = System.nanoTime();

                        System.out.println(
                                "Starting search: "
                                        + provider.getPlatform()
                        );

                        try {
                            List<LearningResource> results =
                                    provider.search(query, languages);

                            long duration =
                                    (System.nanoTime() - start) / 1_000_000;

                            System.out.println(
                                    provider.getPlatform()
                                            + " search took "
                                            + duration
                                            + " ms"
                            );

                            return results == null
                                    ? List.<LearningResource>of()
                                    : results;

                        } catch (Exception e) {

                            System.err.println(
                                    provider.getPlatform()
                                            + " search failed: "
                                            + e.getMessage()
                            );

                            return List.<LearningResource>of();
                        }

                    }, searchExecutor).completeOnTimeout(
                            List.<LearningResource>of(),
                            10,
                            TimeUnit.SECONDS
                    );

            tasks.add(task);
        }

        CompletableFuture.allOf(
                tasks.toArray(new CompletableFuture<?>[0])
        ).join();

        Map<String, LearningResource> uniqueResources =
                new LinkedHashMap<>();

        for (CompletableFuture<List<LearningResource>> task : tasks) {

            for (LearningResource resource : task.join()) {

                if (resource != null
                        && resource.getUrl() != null) {

                    uniqueResources.putIfAbsent(
                            resource.getUrl(),
                            resource
                    );
                }
            }
        }

        List<LearningResource> finalResults =
                new ArrayList<>(uniqueResources.values());

        finalResults.sort(
                Comparator.comparing(
                        LearningResource::getTitle,
                        String.CASE_INSENSITIVE_ORDER
                )
        );

        long totalDuration =
                (System.nanoTime() - overallStart) / 1_000_000;

        System.out.println(
                "TOTAL SEARCH TIME: "
                        + totalDuration + " ms"
        );

        return finalResults;
    }
}