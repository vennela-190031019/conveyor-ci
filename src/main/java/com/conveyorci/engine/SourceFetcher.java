package com.conveyorci.engine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;

import com.conveyorci.engine.ContainerRuntime.ExecResult;
import com.conveyorci.engine.JobStore.Checkout;
import com.conveyorci.github.GitHubClient;

/**
 * Puts a repository's code at a specific commit into a job's /workspace.
 *
 * <p>Downloads GitHub's tarball on the worker, not {@code git clone} inside the container: job
 * images (alpine, node, python...) often don't have git installed, and a tarball of one commit
 * is much smaller than a clone with full history.
 */
@Component
public class SourceFetcher {

    private static final Duration EXTRACT_TIMEOUT = Duration.ofMinutes(5);

    private final GitHubClient github;

    public SourceFetcher(GitHubClient github) {
        this.github = github;
    }

    public void checkout(Checkout source, ContainerRuntime runtime, String containerName, LogBuffer log)
            throws IOException, InterruptedException {
        Path temp = Files.createTempDirectory("conveyor-src-");
        try {
            log.appendLine("$ checkout " + source.owner() + "/" + source.repo() + "@" + source.sha());
            Path archive = temp.resolve("source.tar.gz");
            github.downloadTarball(source.owner(), source.repo(), source.sha(), archive);

            // GitHub wraps everything in one top-level folder (owner-repo-sha/); strip it.
            Path tree = Files.createDirectory(temp.resolve("tree"));
            List<String> tarOutput = new ArrayList<>();
            ExecResult extracted = Processes.run(List.of("tar", "-xzf", archive.toString(), "-C", tree.toString(),
                    "--strip-components=1"), EXTRACT_TIMEOUT, tarOutput::add);
            if (extracted.timedOut() || extracted.exitCode() != 0) {
                throw new IOException("could not extract source archive: " + String.join(" ", tarOutput));
            }

            runtime.copyInto(containerName, tree);
            try (Stream<Path> files = Files.list(tree)) {
                log.appendLine("checked out " + files.count() + " top-level entries into /workspace");
            }
        } finally {
            deleteRecursively(temp);
        }
    }

    private static void deleteRecursively(Path root) {
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {
            // best effort: it's a temp directory
        }
    }
}
