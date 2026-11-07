package metamodel.conformance.web;

import java.time.LocalDate;
import java.util.*;

/** One authoritative compiler for the form, live preview and retained search criteria. */
public record RepositorySearch(Map<String, String> fields) {
    public static final List<String> KEYS = List.of("mode", "q", "language", "owner", "ownerType", "topic", "license", "scope",
            "starsMin", "starsMax", "forksMin", "forksMax", "sizeMin", "sizeMax", "createdFrom", "createdTo", "pushedFrom", "pushedTo", "archived", "forkScope", "sort", "order");
    public RepositorySearch {
        Map<String,String> copy = new LinkedHashMap<>();
        for (String key : KEYS) copy.put(key, Objects.toString(fields.get(key), "").strip());
        copy.putIfAbsent("mode", "BUILDER");
        if (copy.get("mode").isEmpty()) copy.put("mode", "BUILDER");
        if (copy.get("sort").isEmpty()) copy.put("sort", "best-match");
        if (copy.get("order").isEmpty()) copy.put("order", "desc");
        if (copy.get("ownerType").isEmpty()) copy.put("ownerType", "user");
        fields = Collections.unmodifiableMap(copy);
    }
    public static RepositorySearch raw(String query, String sort, String order) {
        return new RepositorySearch(Map.of("mode", "RAW", "q", query, "sort", sort, "order", order));
    }
    public String value(String key) { return fields.getOrDefault(key, ""); }
    public String query() {
        choice("mode", Set.of("BUILDER", "RAW"));
        choice("sort", Set.of("best-match", "stars", "forks", "updated"));
        choice("order", Set.of("asc", "desc"));
        List<String> parts = new ArrayList<>();
        if (!value("q").isEmpty()) parts.add(value("q"));
        if (value("mode").equals("BUILDER")) {
            if (value("q").length() > 256) throw invalid("q", "Keep keywords within 256 characters.");
            if (value("q").contains(":")) throw invalid("q", "Use the filters for qualifiers, or switch to Advanced query for GitHub syntax.");
            if (!value("language").isEmpty()) {
                Map<String,String> unique = new LinkedHashMap<>();
                for (String entry : value("language").split(",", -1)) unique.putIfAbsent(entry.strip().toLowerCase(Locale.ROOT), entry.strip());
                var languages = unique.values();
                if (languages.size() > 5) throw invalid("language", "Use up to five languages, separated by commas.");
                for (String entry : languages) {
                    String language = entry.strip();
                    if (!language.matches("[\\p{L}0-9#+. -]{1,50}")) throw invalid("language", "Enter language names such as Java or C++, separated by commas.");
                    parts.add("language:" + (language.contains(" ") ? "\"" + language + "\"" : language));
                }
            }
            choice("ownerType", Set.of("user", "org"));
            tokens(parts, "owner", value("ownerType"), "[A-Za-z0-9][A-Za-z0-9-]{0,38}");
            tokens(parts, "topic", "topic", "[A-Za-z0-9][A-Za-z0-9-]{0,49}");
            tokens(parts, "license", "license", "[A-Za-z0-9][A-Za-z0-9.-]{0,79}");
            choice("scope", Set.of("", "name", "description", "readme", "name,description", "topics"));
            if (!value("scope").isEmpty()) {
                if (value("q").isEmpty()) throw invalid("q", "Enter keywords to use a search location.");
                parts.add("in:" + value("scope"));
            }
            for (String name : List.of("stars", "forks", "size")) range(parts, name);
            dates(parts, "created"); dates(parts, "pushed");
            choice("archived", Set.of("", "true", "false"));
            if (!value("archived").isEmpty()) parts.add("archived:" + value("archived"));
            choice("forkScope", Set.of("", "true", "only"));
            if (!value("forkScope").isEmpty()) parts.add("fork:" + value("forkScope"));
        }
        String query = String.join(" ", parts);
        if (query.isBlank()) throw invalid("q", "Add keywords or at least one filter to search.");
        if (query.length() > 500) throw invalid("q", "This search is too long. Remove some criteria (500 characters maximum). ");
        return query;
    }
    private void choice(String key, Set<String> allowed) {
        if (!allowed.contains(value(key))) throw invalid(key, "Choose a supported " + key + " option.");
    }
    private void tokens(List<String> parts, String key, String qualifier, String pattern) {
        if (value(key).isBlank()) return;
        Set<String> values = new LinkedHashSet<>(Arrays.asList(value(key).toLowerCase(Locale.ROOT).split("[,\\s]+")));
        if (values.isEmpty()) throw invalid(key, "Enter at least one " + key + " value, or clear the filter.");
        if (values.size() > 5) throw invalid(key, "Use up to five " + key + " values.");
        for (String token : values) {
            if (!token.matches(pattern)) throw invalid(key, "Check the " + key + " values; separate them with commas.");
            parts.add(qualifier + ":" + token);
        }
    }
    private void range(List<String> parts, String name) {
        Long min = number(name + "Min"), max = number(name + "Max");
        if (min != null && max != null && min > max) throw invalid(name + "Max", "Maximum " + name + " must be at least the minimum.");
        if (min != null || max != null) parts.add(name + ":" + (min != null && max != null ? min + ".." + max : min != null ? ">=" + min : "<=" + max));
    }
    private Long number(String key) {
        if (value(key).isEmpty()) return null;
        try { long n = Long.parseLong(value(key)); if (n < 0) throw new NumberFormatException(); return n; }
        catch (NumberFormatException failure) { throw invalid(key, "Enter a non-negative whole number."); }
    }
    private void dates(List<String> parts, String name) {
        LocalDate from = date(name + "From"), to = date(name + "To");
        if (from != null && to != null && from.isAfter(to)) throw invalid(name + "To", "End date must be on or after the start date.");
        if (from != null || to != null) parts.add(name + ":" + (from != null && to != null ? from + ".." + to : from != null ? ">=" + from : "<=" + to));
    }
    private LocalDate date(String key) {
        if (value(key).isEmpty()) return null;
        try { return LocalDate.parse(value(key)); }
        catch (java.time.format.DateTimeParseException failure) { throw invalid(key, "Enter a valid date in YYYY-MM-DD format."); }
    }
    private static Invalid invalid(String field, String message) { return new Invalid(field, message); }
    public static final class Invalid extends IllegalArgumentException {
        public final String field;
        Invalid(String field, String message) { super(message); this.field = field; }
    }
}
