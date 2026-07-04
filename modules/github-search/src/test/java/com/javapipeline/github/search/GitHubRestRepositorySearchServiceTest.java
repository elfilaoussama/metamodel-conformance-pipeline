package com.javapipeline.github.search;

import com.javapipeline.core.CancellationToken;
import com.javapipeline.core.ProgressListener;
import com.javapipeline.core.search.GitHubSearchCriteria;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class GitHubRestRepositorySearchServiceTest {
    @Test
    void mapsRepositoryResultsAndRateLimitHeaders() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/search/repositories", exchange -> {
            assertTrue(exchange.getRequestURI().getRawQuery().contains("language%3AJava"));
            String body = """
                    {
                      "total_count": 1,
                      "incomplete_results": false,
                      "items": [{
                        "full_name": "acme/demo",
                        "description": "Demo repository",
                        "clone_url": "https://github.com/acme/demo.git",
                        "html_url": "https://github.com/acme/demo",
                        "language": "Java",
                        "license": {"spdx_id": "MIT"},
                        "default_branch": "main",
                        "stargazers_count": 42,
                        "forks_count": 7,
                        "size": 123,
                        "fork": false,
                        "archived": false,
                        "updated_at": "2026-01-02T03:04:05Z",
                        "pushed_at": "2026-01-01T00:00:00Z"
                      }]
                    }
                    """;
            exchange.getResponseHeaders().add("x-ratelimit-remaining", "29");
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            URI endpoint = URI.create("http://localhost:" + server.getAddress().getPort() + "/search/repositories");
            var service = new GitHubRestRepositorySearchService(HttpClient.newHttpClient(), endpoint);
            var criteria = new GitHubSearchCriteria(
                    "", "Java", "", "", "", null, null, null, null, null, null,
                    null, null, GitHubSearchCriteria.ForkMode.EXCLUDE,
                    GitHubSearchCriteria.ArchiveMode.EXCLUDE,
                    GitHubSearchCriteria.Sort.STARS, GitHubSearchCriteria.Order.DESCENDING, 1);

            var response = service.search(
                    criteria, new char[0], ProgressListener.NONE, CancellationToken.NONE);

            assertEquals(1, response.totalCount());
            assertEquals(29, response.rateLimitRemaining());
            assertEquals("acme/demo", response.repositories().get(0).fullName());
            assertEquals("MIT", response.repositories().get(0).license());
        } finally {
            server.stop(0);
        }
    }
}
