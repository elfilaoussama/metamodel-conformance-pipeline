package com.javapipeline.github.search;

import com.javapipeline.core.search.GitHubSearchCriteria;

import java.util.ArrayList;
import java.util.List;

final class GitHubSearchQueryBuilder {
    private GitHubSearchQueryBuilder() { }

    static String build(GitHubSearchCriteria criteria) {
        List<String> terms = new ArrayList<>();
        if (!criteria.keywords().isBlank()) {
            terms.add(criteria.keywords());
            terms.add("in:name,description,readme");
        }
        qualifier(terms, "language", criteria.language());
        if (!criteria.owner().isBlank()) {
            String owner = criteria.owner();
            if (owner.regionMatches(true, 0, "org:", 0, 4)
                    || owner.regionMatches(true, 0, "user:", 0, 5)) {
                terms.add(owner);
            } else {
                qualifier(terms, "user", owner);
            }
        }
        qualifier(terms, "topic", criteria.topic());
        if (!criteria.license().isBlank()) {
            for (String token : criteria.license().split("[\\s,;|]+")) {
                String t = token.trim();
                if (!t.isEmpty() && !t.equalsIgnoreCase("OR") && !t.equalsIgnoreCase("AND")) {
                    terms.add("license:" + t);
                }
            }
        }
        range(terms, "stars", criteria.minStars(), criteria.maxStars());
        range(terms, "forks", criteria.minForks(), criteria.maxForks());
        range(terms, "size", criteria.minSizeKb(), criteria.maxSizeKb());
        if (criteria.createdAfter() != null) terms.add("created:>=" + criteria.createdAfter());
        if (criteria.pushedAfter() != null) terms.add("pushed:>=" + criteria.pushedAfter());
        switch (criteria.forkMode()) {
            case EXCLUDE -> terms.add("fork:false");
            case INCLUDE -> terms.add("fork:true");
            case ONLY -> terms.add("fork:only");
        }
        switch (criteria.archiveMode()) {
            case EXCLUDE -> terms.add("archived:false");
            case ONLY -> terms.add("archived:true");
            case INCLUDE -> { }
        }
        return String.join(" ", terms);
    }

    private static void qualifier(List<String> terms, String name, String value) {
        if (!value.isBlank()) terms.add(name + ":" + quoteIfNeeded(value));
    }

    private static String quoteIfNeeded(String value) {
        if (!value.chars().anyMatch(Character::isWhitespace)) return value;
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static void range(List<String> terms, String name, Integer minimum, Integer maximum) {
        if (minimum != null && maximum != null) terms.add(name + ":" + minimum + ".." + maximum);
        else if (minimum != null) terms.add(name + ":>=" + minimum);
        else if (maximum != null) terms.add(name + ":<=" + maximum);
    }
}
