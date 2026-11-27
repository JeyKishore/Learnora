package com.learnora.learnora.service.providers;

import com.learnora.learnora.model.LearningResource;

import java.util.List;

public interface LearningResourceProvider {

    String getPlatform();

    List<LearningResource> search(
            String query,
            String languages
    );
}