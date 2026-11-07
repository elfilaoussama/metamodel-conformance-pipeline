package metamodel.conformance.web;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/** Loopback sessions are for a trusted single operator, not shared-host authentication. */
@Component
public final class LocalSessionFilter extends OncePerRequestFilter {
    @Override protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        res.setHeader("X-Content-Type-Options", "nosniff");
        res.setHeader("Referrer-Policy", "same-origin");
        res.setHeader("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'");
        res.setHeader("Cache-Control", "no-store");
        String expectedOrigin = "http://" + req.getHeader("Host");
        if (!Set.of("127.0.0.1", "localhost").contains(req.getServerName())
                || "cross-site".equals(req.getHeader("Sec-Fetch-Site"))
                || (req.getHeader("Origin") != null && !expectedOrigin.equals(req.getHeader("Origin")))) {
            res.sendError(403); return;
        }
        boolean api = req.getRequestURI().startsWith("/api/");
        boolean form = req.getRequestURI().startsWith("/ui/");
        if ((api && !req.getRequestURI().equals("/api/v1/session")) || form) {
            HttpSession session = req.getSession(false);
            if (session == null || session.getAttribute("csrf") == null) { res.sendError(401); return; }
            if (!Set.of("GET", "HEAD").contains(req.getMethod())) {
                if (req.getContentLengthLong() > 16384 || req.getContentLengthLong() < 0) { res.sendError(413); return; }
                String expectedType = api ? "application/json" : "application/x-www-form-urlencoded";
                if (req.getContentType() == null || !req.getContentType().startsWith(expectedType)) { res.sendError(415); return; }
                String token = api ? req.getHeader("X-CSRF-Token") : req.getParameter("_csrf");
                String wanted = session.getAttribute("csrf").toString();
                if (token == null || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), wanted.getBytes(StandardCharsets.UTF_8))) {
                    res.sendError(403); return;
                }
            }
        }
        chain.doFilter(req, res);
    }
    public static String csrfToken(HttpServletRequest request) {
        HttpSession session = request.getSession(true);
        if (session.getAttribute("csrf") == null) session.setAttribute("csrf", java.util.UUID.randomUUID().toString());
        return session.getAttribute("csrf").toString();
    }
}
