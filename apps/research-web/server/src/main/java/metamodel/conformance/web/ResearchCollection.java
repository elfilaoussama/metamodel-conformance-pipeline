package metamodel.conformance.web;

import java.time.Instant;
import java.util.*;

public record ResearchCollection(String id, String name, long version, String updated,
                                 List<Project> projects, Set<String> selected, List<Project> candidates) {
    public ResearchCollection {
        requireId(id);
        if (name == null || name.isBlank() || name.length() > 120) throw new IllegalArgumentException("Enter a collection name (1–120 characters).");
        name = name.strip(); projects = List.copyOf(projects); selected = Set.copyOf(selected); candidates = List.copyOf(candidates);
        Set<String> ids = new HashSet<>(), identities = new HashSet<>();
        for (Project project : projects) if (!ids.add(project.id()) || !identities.add(project.identity())) throw new IllegalArgumentException("Duplicate collection project.");
        if (!ids.containsAll(selected)) throw new IllegalArgumentException("Selection contains a project outside this collection.");
    }
    ResearchCollection change(List<Project> projects, Set<String> selected, List<Project> candidates) {
        return new ResearchCollection(id, name, version + 1, Instant.now().toString(), projects, selected, candidates);
    }
    public static void requireId(String id) {
        if (id == null || !id.matches("[0-9a-f-]{36}")) throw new IllegalArgumentException("Invalid workspace identifier.");
    }
    public record Project(String id, String label, String origin, String location, String revision,
                          String subdirectory, String language, String discoveryId) {
        public Project {
            requireId(id);
            if (origin == null || language == null || !Set.of("GITHUB", "LOCAL").contains(origin) || !Set.of("JAVA", "PYTHON", "CPP", "UNSPECIFIED").contains(language))
                throw new IllegalArgumentException("Select a supported source type and language.");
            if (label == null || label.isBlank() || label.length() > 120 || location == null || location.isBlank())
                throw new IllegalArgumentException("Project name and source are required (name up to 120 characters).");
            if (origin.equals("GITHUB")) {
                RepositoryRequest validated = new RepositoryRequest(label, location, revision, subdirectory, false, null, discoveryId);
                location = validated.url(); revision = validated.revision(); subdirectory = validated.subdirectory(); discoveryId = validated.discoveryId();
            } else { revision = ""; subdirectory = ""; discoveryId = null; }
        }
        String identity() {
            String coordinate = origin.equals("GITHUB") || System.getProperty("os.name").startsWith("Windows") ? location.toLowerCase(Locale.ROOT) : location;
            return origin + "\n" + coordinate + "\n" + revision + "\n" + subdirectory + "\n" + language;
        }
        public String sourceLabel() { return origin.equals("GITHUB") ? "GitHub" : "Local source"; }
    }
    public record Review(String id, String collectionId, String collectionName, long collectionVersion,
                         String operation, String created, String engineSha256, List<Project> projects) {
        public Review { requireId(id); requireId(collectionId); projects = List.copyOf(projects); }
        public String requestKey(Project project) { return id + "-" + project.id(); }
    }
}
