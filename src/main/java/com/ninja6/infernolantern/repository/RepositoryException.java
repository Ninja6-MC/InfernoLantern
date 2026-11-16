package com.ninja6.infernolantern.repository;

/** A failed request never implies that a Minecraft effect was committed. */
public class RepositoryException extends RuntimeException {
    public RepositoryException(String message) { super(message); }
    public RepositoryException(String message, Throwable cause) { super(message, cause); }
    public static final class Conflict extends RepositoryException {
        public Conflict(String message) { super(message); }
    }
}
