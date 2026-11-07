package metamodel.conformance.web;

import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Service;
import java.time.Instant;

/** One session-associated setup shared by every repository-discovery entry point. */
@Service
public final class AnalysisSetup {
    private final CollectionService collections;
    public AnalysisSetup(CollectionService collections) { this.collections = collections; }
    public String currentOrCreate(HttpSession session) throws Exception {
        synchronized (session) {
            String id = (String) session.getAttribute("workflowCollection");
            if (id == null) {
                id = collections.create("Analysis setup " + Instant.now().toString().substring(0,16).replace('T',' ')).id();
                session.setAttribute("workflowCollection", id);
            }
            return id;
        }
    }
}
