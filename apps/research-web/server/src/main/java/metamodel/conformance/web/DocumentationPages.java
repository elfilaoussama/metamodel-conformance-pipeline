package metamodel.conformance.web;

import org.commonmark.Extension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.*;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Renders the reviewed repository manual. Request paths never select host files. */
@Controller
public final class DocumentationPages {
    public record Page(String group, String path, String title) { }
    public record Section(String id, String title) { }
    public record Chapter(Page page, String html, List<Section> sections, String text) { }
    private static final List<Page> PAGES = List.of(
            new Page("Start here", "README.md", "Documentation"),
            new Page("Tutorial", "tutorials/first-analysis.md", "Your first analysis"),
            new Page("Guides", "guides/github-sources.md", "Find and acquire repositories"),
            new Page("Guides", "guides/interpret-results.md", "Interpret results"),
            new Page("Guides", "guides/reproduce.md", "Replay and reproduce"),
            new Page("Explanation", "explanation/architecture.md", "Pipeline architecture"),
            new Page("Explanation", "explanation/methodology.md", "Scientific methodology"),
            new Page("Explanation", "explanation/rule-profiles.md", "Replaceable rule profiles"),
            new Page("Explanation", "explanation/interface-design.md", "Interface design rationale"),
            new Page("Reference", "reference/operations.md", "Configuration and troubleshooting"),
            new Page("Reference", "reference/capabilities.md", "Capabilities and limitations"),
            new Page("Reference", "reference/web-api.md", "Web API"),
            new Page("Reference", "reference/source-map.md", "Source and documentation map"));
    private final Map<String, Chapter> chapters;

    public DocumentationPages() throws IOException {
        List<Extension> extensions = List.of(TablesExtension.create());
        var parser = Parser.builder().extensions(extensions).build();
        var loaded = new LinkedHashMap<String, Chapter>();
        for (Page page : PAGES) {
            String source = new ClassPathResource("documentation/" + page.path()).getContentAsString(StandardCharsets.UTF_8);
            Node document = parser.parse(source);
            List<Section> sections = new ArrayList<>();
            Set<String> identifiers = new HashSet<>();
            document.accept(new AbstractVisitor() {
                @Override public void visit(Link link) {
                    link.setDestination(destination(page.path(), link.getDestination())); visitChildren(link);
                }
                @Override public void visit(Image image) {
                    image.setDestination(destination(page.path(), image.getDestination())); visitChildren(image);
                }
            });
            // IDs are assigned by the renderer using source-order headings, including duplicate headings.
            var headingRenderer = HtmlRenderer.builder().extensions(extensions).escapeHtml(true).sanitizeUrls(true)
                    .attributeProviderFactory(context -> (node, tag, attributes) -> {
                        if (node instanceof Heading heading) {
                            String title = plain(heading);
                            String base = title.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
                            if (base.isEmpty()) base = "section";
                            String id = base; int suffix = 1;
                            while (!identifiers.add(id)) id = base + "-" + suffix++;
                            attributes.put("id", id);
                            if (heading.getLevel() == 2) sections.add(new Section(id, title));
                        }
                    }).build();
            loaded.put(page.path(), new Chapter(page, headingRenderer.render(document), List.copyOf(sections), source));
        }
        chapters = Collections.unmodifiableMap(loaded);
    }

    @GetMapping({"/documentation", "/documentation/{section}/{file}.md"})
    public String chapter(@PathVariable(required=false) String section, @PathVariable(required=false) String file,
                          @RequestParam(defaultValue="") String q, Model model) {
        String path = section == null ? "README.md" : section + "/" + file + ".md";
        Chapter chapter = chapters.get(path);
        if (chapter == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That documentation chapter is not available. Choose a chapter from the manual.");
        if (q.length() > 120) throw new IllegalArgumentException("Keep documentation search within 120 characters.");
        model.addAttribute("chapter", chapter); model.addAttribute("pages", PAGES); model.addAttribute("q", q.strip());
        model.addAttribute("matches", chapters.values().stream().filter(c -> !q.isBlank()
                && c.text().toLowerCase(Locale.ROOT).contains(q.strip().toLowerCase(Locale.ROOT))).toList());
        int index = PAGES.indexOf(chapter.page());
        model.addAttribute("previous", index > 0 ? PAGES.get(index - 1) : null);
        model.addAttribute("next", index + 1 < PAGES.size() ? PAGES.get(index + 1) : null);
        return "documentation";
    }

    @GetMapping(value="/documentation/assets/architecture.svg", produces="image/svg+xml")
    @ResponseBody
    public byte[] diagram() throws IOException {
        return new ClassPathResource("documentation/assets/architecture.svg").getContentAsByteArray();
    }

    private static String plain(Node node) {
        StringBuilder text = new StringBuilder();
        node.accept(new AbstractVisitor() {
            @Override public void visit(Text value) { text.append(value.getLiteral()); }
            @Override public void visit(Code value) { text.append(value.getLiteral()); }
        });
        return text.toString();
    }
    static String destination(String current, String destination) {
        URI target = URI.create("/" + current).resolve(destination).normalize();
        if (target.isAbsolute() || destination.startsWith("#") || destination.startsWith("/")) return destination;
        String path = target.getPath().substring(1);
        if (PAGES.stream().anyMatch(p -> p.path().equals(path)))
            return (path.equals("README.md") ? "/documentation" : "/documentation/" + path)
                    + (target.getFragment() == null ? "" : "#" + target.getFragment());
        if (path.equals("assets/architecture.svg")) return "/documentation/" + path;
        // Unregistered links are rejected during qualification; they cannot select arbitrary resources.
        return destination;
    }
}
