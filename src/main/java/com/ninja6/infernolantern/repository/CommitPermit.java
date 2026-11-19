package com.ninja6.infernolantern.repository;

/** Revocable authorization fence containing no Paper state or callbacks. */
public final class CommitPermit {
    private volatile boolean revoked;

    public void revoke() {
        revoked = true;
    }

    void requireCurrent() {
        if (revoked) throw new RepositoryException.CommitRevoked();
    }
}
