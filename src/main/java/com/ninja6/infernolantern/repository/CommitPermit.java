package com.ninja6.infernolantern.repository;

/** Revocable authorization fence containing no Paper state or callbacks. */
public final class CommitPermit {
    private final CommitPermit[] dependencies;
    private volatile boolean revoked;

    public CommitPermit() {
        dependencies = new CommitPermit[0];
    }

    private CommitPermit(CommitPermit[] dependencies) {
        this.dependencies = dependencies;
    }

    public static CommitPermit allOf(CommitPermit... permits) {
        CommitPermit[] dependencies = permits.clone();
        for (CommitPermit permit : dependencies) java.util.Objects.requireNonNull(permit);
        return new CommitPermit(dependencies);
    }

    public void revoke() {
        revoked = true;
    }

    public void requireValid() {
        if (revoked) throw new RepositoryException.CommitRevoked();
        for (CommitPermit dependency : dependencies) dependency.requireValid();
    }
}
