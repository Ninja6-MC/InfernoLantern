package com.ninja6.infernolantern.repository;

/** A failed request never implies that a Minecraft effect was committed. */
public class RepositoryException extends RuntimeException {
    public RepositoryException(String message) { super(message); }
    public RepositoryException(String message, Throwable cause) { super(message, cause); }
    /** A catalog guard rejected this transaction before any writes; physical compensation still needs proof. */
    public static final class CatalogSuperseded extends RepositoryException {
        public CatalogSuperseded(){super("Catalog changed before completion");}
    }
    /** A revocable authorization fence rejected admission. No durable commit was attempted. */
    public static final class CommitRevoked extends RepositoryException {
        public CommitRevoked() { super("Session authorization revoked before commit"); }
    }
    public static final class Conflict extends RepositoryException {
        public Conflict(String message) { super(message); }
    }
}
