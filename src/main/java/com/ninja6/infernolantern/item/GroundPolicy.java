package com.ninja6.infernolantern.item;

/** Validated effective item policy; variant catalog/configuration remains a separate concern. */
public record GroundPolicy(boolean survivesFireAndLava, boolean despawns) {
    public static final GroundPolicy DEFAULT = new GroundPolicy(true, true);
}
