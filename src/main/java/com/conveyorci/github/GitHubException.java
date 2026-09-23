package com.conveyorci.github;

public class GitHubException extends RuntimeException {

    private final int status;

    public GitHubException(String message, int status) {
        super(message);
        this.status = status;
    }

    public int getStatus() {
        return status;
    }
}
