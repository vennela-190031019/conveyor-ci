package com.conveyorci.github;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Minimal GitHub REST client built on the JDK's HttpClient: fetch a file at a commit, download a
 * repository tarball, and set a commit status. Works without a token for public repositories
 * (rate-limited); a token is required for private repositories and for commit statuses.
 */
@Component
public class GitHubClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofMinutes(5);

    private final String apiUrl;
    private final String token;
    private final ObjectMapper json;
    private final HttpClient http;

    public GitHubClient(@Value("${conveyor.github.api-url:https://api.github.com}") String apiUrl,
                        @Value("${conveyor.github.token:}") String token,
                        ObjectMapper json) {
        this.apiUrl = apiUrl.endsWith("/") ? apiUrl.substring(0, apiUrl.length() - 1) : apiUrl;
        this.token = token == null ? "" : token.strip();
        this.json = json;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL) // tarballs redirect to codeload.github.com
                .build();
    }

    /** Raw contents of {@code path} at {@code ref}, or empty if the file doesn't exist there. */
    public Optional<String> fetchFile(String owner, String repo, String path, String ref)
            throws IOException, InterruptedException {
        HttpRequest request = request(repoPath(owner, repo) + "/contents/" + path + "?ref=" + encode(ref))
                .header("Accept", "application/vnd.github.raw+json")
                .GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 404) {
            return Optional.empty();
        }
        requireSuccess(response.statusCode(), "fetch " + path + " from " + owner + "/" + repo);
        return Optional.of(response.body());
    }

    /** Downloads the repository at {@code ref} as a .tar.gz to {@code target}. */
    public void downloadTarball(String owner, String repo, String ref, Path target)
            throws IOException, InterruptedException {
        HttpRequest request = request(repoPath(owner, repo) + "/tarball/" + encode(ref))
                .timeout(DOWNLOAD_TIMEOUT)
                .GET().build();
        HttpResponse<Path> response = http.send(request, HttpResponse.BodyHandlers.ofFile(target));
        requireSuccess(response.statusCode(), "download " + owner + "/" + repo + "@" + ref);
    }

    /**
     * Sets a commit status (the ✓/✗ next to a commit on GitHub).
     *
     * @param state one of pending, success, failure, error
     */
    public void createStatus(String owner, String repo, String sha, String state, String description,
                             String targetUrl, String context) throws IOException, InterruptedException {
        String body = toJson(Map.of(
                "state", state,
                "description", description.length() > 140 ? description.substring(0, 137) + "..." : description,
                "target_url", targetUrl,
                "context", context));
        HttpRequest request = request(repoPath(owner, repo) + "/statuses/" + encode(sha))
                .header("Accept", "application/vnd.github+json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        requireSuccess(response.statusCode(), "set status on " + owner + "/" + repo + "@" + sha);
    }

    private HttpRequest.Builder request(String path) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(apiUrl + path))
                .timeout(TIMEOUT)
                .header("User-Agent", "conveyor-ci")
                .header("X-GitHub-Api-Version", "2022-11-28");
        if (!token.isEmpty()) {
            builder.header("Authorization", "Bearer " + token);
        }
        return builder;
    }

    private static String repoPath(String owner, String repo) {
        return "/repos/" + encode(owner) + "/" + encode(repo);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String toJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void requireSuccess(int status, String action) {
        if (status < 200 || status >= 300) {
            String hint = status == 401 || status == 403 || status == 404
                    ? " (check that conveyor.github.token is set and can access this repository)" : "";
            throw new GitHubException("GitHub returned HTTP " + status + " trying to " + action + hint, status);
        }
    }
}
