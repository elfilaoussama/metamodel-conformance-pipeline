package com.javapipeline.github.search;

import com.javapipeline.core.search.GitHubSearchCriteria;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertTrue;

class GitHubSearchQueryBuilderTest {
    @Test
    void buildsAdvancedRepositoryQualifiers() {
        var criteria = new GitHubSearchCriteria(
                "analysis pipeline", "Java", "org:apache", "static-analysis", "apache-2.0",
                100, 5000, 5, null, 50, 20_000,
                LocalDate.of(2020, 1, 1), LocalDate.of(2024, 1, 1),
                GitHubSearchCriteria.ForkMode.EXCLUDE,
                GitHubSearchCriteria.ArchiveMode.EXCLUDE,
                GitHubSearchCriteria.Sort.STARS,
                GitHubSearchCriteria.Order.DESCENDING,
                250);

        String query = GitHubSearchQueryBuilder.build(criteria);

        assertTrue(query.contains("analysis pipeline in:name,description,readme"));
        assertTrue(query.contains("language:Java"));
        assertTrue(query.contains("org:apache"));
        assertTrue(query.contains("stars:100..5000"));
        assertTrue(query.contains("fork:false"));
        assertTrue(query.contains("archived:false"));
    }
}
