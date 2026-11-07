package metamodel.conformance.web;

import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import java.util.NoSuchElementException;

@ControllerAdvice(assignableTypes = {WorkbenchPages.class, RepositoryPages.class, CollectionPages.class, WorkflowPages.class, DocumentationPages.class})
public final class PageErrors {
    @ExceptionHandler(Exception.class)
    public String error(Exception failure, Model model, jakarta.servlet.http.HttpServletResponse response) {
        int code = failure instanceof org.springframework.web.server.ResponseStatusException status ? status.getStatusCode().value()
                : failure instanceof NoSuchElementException ? 404 : failure instanceof IllegalStateException ? 409
                : failure instanceof IllegalArgumentException || failure instanceof java.io.IOException
                || failure instanceof org.springframework.web.method.annotation.MethodArgumentTypeMismatchException ? 400 : 500;
        response.setStatus(code); model.addAttribute("code", code);
        model.addAttribute("message", code == 500 ? "The page could not be rendered. Inspect the local service log." : failure.getMessage());
        if (code == 500) org.slf4j.LoggerFactory.getLogger(PageErrors.class).error("Page request failed", failure);
        return "workbench-error";
    }
}
