package metamodel.conformance.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.view.RedirectView;
import org.springframework.http.HttpStatus;
import java.util.Map;

/** Task-oriented entry point. Saved collections remain an optional persistence mechanism. */
@Controller
public final class WorkflowPages {
    private final AnalysisSetup setup;
    private final Catalog catalog;
    public WorkflowPages(AnalysisSetup setup, Catalog catalog) { this.setup = setup; this.catalog = catalog; }
    @GetMapping("/workflow")
    public String home(HttpServletRequest request, Model model) throws Exception {
        model.addAttribute("csrf", LocalSessionFilter.csrfToken(request));
        model.addAttribute("activeJobs", catalog.active());
        model.addAttribute("recentRuns", catalog.list().stream().limit(5).toList());
        model.addAttribute("draft", request.getSession().getAttribute("workflowCollection"));
        return "workflow";
    }
    @PostMapping("/ui/workflow/discover")
    public RedirectView discover(@RequestParam Map<String,String> fields, HttpServletRequest request) throws Exception {
        if (fields.size() != 1 || !fields.containsKey("_csrf")) throw new IllegalArgumentException("Unsupported setup field.");
        String id = setup.currentOrCreate(request.getSession());
        var redirect = new RedirectView("/repositories?collection=" + id);
        redirect.setStatusCode(HttpStatus.SEE_OTHER); return redirect;
    }
}
