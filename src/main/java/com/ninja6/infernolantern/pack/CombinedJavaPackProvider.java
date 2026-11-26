package com.ninja6.infernolantern.pack;

import com.ninja6.infernolantern.repository.CommitPermit;
import org.bukkit.entity.Player;

import java.util.Objects;

/**
 * Server-managed Java delivery owner registered as a Paper service by the configured plugin.
 * Implementations handle the pack request and its refusal, failure and absence policy.
 */
public interface CombinedJavaPackProvider {
    /**
     * Inspect the exact current connection on the Paper primary thread, without blocking.
     * Return null while pending. A loaded receipt requires terminal success for the owner's
     * exact request and verified archive bytes. The expected identity alone is not evidence.
     */
    Receipt observe(Player player, PackPlan.Identity expected);

    /**
     * The permit belongs to this receipt and must remain stable while it is current.
     * Revoke it before withdrawing, replacing or invalidating the observed delivery,
     * including on disconnect and provider shutdown. A replacement requires a new permit.
     */
    record Receipt(Player player, PackPlan.Identity identity, PaperPackSessions.Status status, CommitPermit permit) {
        public Receipt {
            Objects.requireNonNull(player);
            Objects.requireNonNull(identity);
            Objects.requireNonNull(status);
            Objects.requireNonNull(permit);
        }
    }
}
