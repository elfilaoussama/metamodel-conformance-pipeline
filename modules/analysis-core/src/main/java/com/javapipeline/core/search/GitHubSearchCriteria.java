package com.javapipeline.core.search;

import java.time.LocalDate;
import java.util.Objects;

public record GitHubSearchCriteria(
        String keywords,
        String language,
        String owner,
        String topic,
        String license,
        Integer minStars,
        Integer maxStars,
        Integer minForks,
        Integer maxForks,
        Integer minSizeKb,
        Integer maxSizeKb,
        LocalDate createdAfter,
        LocalDate pushedAfter,
        ForkMode forkMode,
        ArchiveMode archiveMode,
        Sort sort,
        Order order,
        int resultLimit
) {
    public enum ForkMode { EXCLUDE, INCLUDE, ONLY }
    public enum ArchiveMode { EXCLUDE, INCLUDE, ONLY }
    public enum Sort { BEST_MATCH, STARS, FORKS, UPDATED }
    public enum Order { DESCENDING, ASCENDING }

    public GitHubSearchCriteria {
        keywords = clean(keywords);
        language = clean(language);
        owner = clean(owner);
        topic = clean(topic);
        license = clean(license);
        forkMode = Objects.requireNonNullElse(forkMode, ForkMode.EXCLUDE);
        archiveMode = Objects.requireNonNullElse(archiveMode, ArchiveMode.EXCLUDE);
        sort = Objects.requireNonNullElse(sort, Sort.BEST_MATCH);
        order = Objects.requireNonNullElse(order, Order.DESCENDING);
        requireRange(minStars, maxStars, "stars");
        requireRange(minForks, maxForks, "forks");
        requireRange(minSizeKb, maxSizeKb, "size");
        if (resultLimit < 1 || resultLimit > 1_000) {
            throw new IllegalArgumentException("Result limit must be between 1 and 1000");
        }
        if (keywords.isEmpty() && language.isEmpty() && owner.isEmpty() && topic.isEmpty()
                && license.isEmpty() && minStars == null && maxStars == null
                && minForks == null && maxForks == null && minSizeKb == null && maxSizeKb == null
                && createdAfter == null && pushedAfter == null) {
            throw new IllegalArgumentException("Enter keywords or at least one search filter");
        }
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static void requireRange(Integer minimum, Integer maximum, String label) {
        if (minimum != null && minimum < 0 || maximum != null && maximum < 0) {
            throw new IllegalArgumentException(label + " values cannot be negative");
        }
        if (minimum != null && maximum != null && minimum > maximum) {
            throw new IllegalArgumentException(label + " minimum cannot exceed maximum");
        }
    }
}
