package com.conveyorci.github;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * A tiny in-process stand-in for the GitHub REST API (JDK HttpServer, no extra dependencies):
 * serves files and tarballs per commit, and records commit statuses.
 */
public class FakeGitHub implements AutoCloseable {

    private static final Pattern CONTENTS = Pattern.compile("/repos/([^/]+)/([^/]+)/contents/(.+)");
    private static final Pattern TARBALL = Pattern.compile("/repos/([^/]+)/([^/]+)/tarball/([^/]+)");
    private static final Pattern STATUSES = Pattern.compile("/repos/([^/]+)/([^/]+)/statuses/([^/]+)");

    public record StatusPost(String repo, String sha, String body) {
    }

    private final HttpServer server;
    private final Map<String, String> files = new ConcurrentHashMap<>();   // "owner/repo@sha:path" -> content
    private final Map<String, byte[]> tarballs = new ConcurrentHashMap<>(); // "owner/repo@sha" -> tar.gz
    private final List<StatusPost> statuses = new CopyOnWriteArrayList<>();
    private final AtomicInteger statusFailuresToInject = new AtomicInteger();

    public FakeGitHub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void putFile(String repo, String sha, String path, String content) {
        files.put(repo + "@" + sha + ":" + path, content);
    }

    /** Serves a tarball shaped like GitHub's: every file under one top-level "owner-repo-sha/" folder. */
    public void putRepository(String repo, String sha, Map<String, String> contents)
            throws IOException, InterruptedException {
        Path temp = Files.createTempDirectory("fake-github-");
        String top = repo.replace('/', '-') + "-" + sha.substring(0, 7);
        for (Map.Entry<String, String> file : contents.entrySet()) {
            Path target = temp.resolve(top).resolve(file.getKey());
            Files.createDirectories(target.getParent());
            Files.writeString(target, file.getValue());
        }
        Path archive = temp.resolve("repo.tar.gz");
        Process tar = new ProcessBuilder("tar", "-czf", archive.toString(), "-C", temp.toString(), top)
                .redirectErrorStream(true).start();
        if (tar.waitFor() != 0) {
            throw new IOException("tar failed: " + new String(tar.getInputStream().readAllBytes()));
        }
        tarballs.put(repo + "@" + sha, Files.readAllBytes(archive));
    }

    /** The next {@code count} status posts fail with HTTP 502. */
    public void failNextStatusPosts(int count) {
        statusFailuresToInject.set(count);
    }

    public List<StatusPost> statuses() {
        return List.copyOf(statuses);
    }

    public List<String> statesFor(String sha) {
        return statuses.stream().filter(s -> s.sha().equals(sha))
                .map(s -> s.body().replaceAll(".*\"state\"\\s*:\\s*\"([a-z]+)\".*", "$1"))
                .toList();
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getRawPath();
        String query = exchange.getRequestURI().getQuery();
        Matcher m;
        if ("GET".equals(exchange.getRequestMethod()) && (m = CONTENTS.matcher(path)).matches()) {
            String ref = query == null ? "" : query.replaceFirst("^ref=", "");
            String content = files.get(m.group(1) + "/" + m.group(2) + "@" + ref + ":" + m.group(3));
            respond(exchange, content == null ? 404 : 200,
                    content == null ? "{\"message\":\"Not Found\"}" : content);
        } else if ("GET".equals(exchange.getRequestMethod()) && (m = TARBALL.matcher(path)).matches()) {
            byte[] tarball = tarballs.get(m.group(1) + "/" + m.group(2) + "@" + m.group(3));
            if (tarball == null) {
                respond(exchange, 404, "{\"message\":\"Not Found\"}");
            } else {
                exchange.sendResponseHeaders(200, tarball.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(tarball);
                }
            }
        } else if ("POST".equals(exchange.getRequestMethod()) && (m = STATUSES.matcher(path)).matches()) {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (statusFailuresToInject.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                respond(exchange, 502, "{\"message\":\"Bad Gateway\"}");
            } else {
                statuses.add(new StatusPost(m.group(1) + "/" + m.group(2), m.group(3), body));
                respond(exchange, 201, body);
            }
        } else {
            respond(exchange, 404, "{\"message\":\"Not Found\"}");
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
