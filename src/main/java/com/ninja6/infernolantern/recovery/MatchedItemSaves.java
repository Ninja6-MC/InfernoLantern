package com.ninja6.infernolantern.recovery;

import com.ninja6.infernolantern.repository.RepositoryRecords.Ground;
import com.ninja6.infernolantern.repository.RepositoryRecords.Operation;
import com.ninja6.infernolantern.repository.RepositoryRecords.TowerSnapshot;
import com.ninja6.infernolantern.scheduling.WorldScheduler.Anchor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import com.ninja6.infernolantern.recovery.PaperRecoveryPort.CoverageCheck;
import com.ninja6.infernolantern.recovery.PaperRecoveryPort.CoverageProof;
import com.ninja6.infernolantern.recovery.PaperRecoveryPort.GroundView;
import com.ninja6.infernolantern.recovery.PaperRecoveryPort.ItemView;
import com.ninja6.infernolantern.recovery.ReconciliationService.Coverage;
import com.ninja6.infernolantern.recovery.ReconciliationService.Physical;

/** Matches an exact Paper item observation against independently inspected durable saves. */
public final class MatchedItemSaves implements CoverageCheck {
    private static final Pattern SHA256=Pattern.compile("[0-9a-f]{64}");

    /** The capture identity is allocated at quiescence, not inferred from file names or times. */
    public record Source(UUID captureId,String sha256) {
        public Source {
            Objects.requireNonNull(captureId);
            Objects.requireNonNull(sha256);
            if(!SHA256.matcher(sha256).matches())throw new IllegalArgumentException("Expected SHA-256");
        }
    }

    /** Exact journal and frozen representation decoded from the inspected SQLite capture. */
    public record Database(Source source,Operation operation,TowerSnapshot tower) {
        public Database {
            Objects.requireNonNull(source);
            Objects.requireNonNull(operation);
            Objects.requireNonNull(tower);
        }
    }

    public enum Custody { EXACT, INCOMPLETE, CONFLICTING }

    /** Exact custody covers all other relevant slots, containers, chunks and entities without finding a conflicting representation. */
    public record WorldScope(Source source,Anchor anchor,Custody custody) {
        public WorldScope {
            Objects.requireNonNull(source);
            Objects.requireNonNull(anchor);
            Objects.requireNonNull(custody);
        }
    }

    /** Exact saved slot, in the same item encoding as the pinned Paper observer. */
    public record PlayerSlot(Source source,UUID player,int slot,String item) {
        public PlayerSlot {
            Objects.requireNonNull(source);
            Objects.requireNonNull(player);
            Objects.requireNonNull(item);
            if(slot<0||slot>255)throw new IllegalArgumentException("Slot bounds");
        }
    }

    /** A present record with a null item attests to an inspected absence in the complete scope. */
    public record GroundEntity(Source source,Ground reference,GroundView item) {
        public GroundEntity {
            Objects.requireNonNull(source);
            Objects.requireNonNull(reference);
        }
    }

    /** Each source retains the identity and digest assigned by the independent capture. */
    public record Capture(Database database,WorldScope world,PlayerSlot player,GroundEntity ground) {}

    private final Supplier<Optional<Capture>> current;

    /**
     * Supply preloaded, verified evidence without owner-thread I/O. The capture source must
     * keep the operation scope fenced and withdraw evidence if that scope changes or reopens.
     */
    public MatchedItemSaves(Supplier<Optional<Capture>> current) {
        this.current=Objects.requireNonNull(current);
    }

    @Override
    public CoverageProof assess(Operation operation,TowerSnapshot tower,RecoveryEnvelope.Context context,ItemView items) {
        Capture capture=current.get().orElse(null);
        if(capture==null||capture.database()==null||capture.world()==null){
            return new CoverageProof(Coverage.INCOMPLETE,"Database or world save scope unavailable");
        }
        Database database=capture.database();
        WorldScope world=capture.world();
        PlayerSlot player=capture.player();
        GroundEntity ground=capture.ground();
        UUID id=database.source().captureId();
        if(!operation.pending()||!operation.equals(database.operation())||!tower.equals(database.tower())
                ||!context.anchor().equals(world.anchor())||!id.equals(world.source().captureId())
                ||player!=null&&!id.equals(player.source().captureId())
                ||ground!=null&&!id.equals(ground.source().captureId())){
            return new CoverageProof(Coverage.UNMATCHED,"Save capture or journal snapshot differs");
        }
        Ground reference=operation.before().ground()!=null?operation.before().ground():operation.intent().after().ground();
        var slot=context.inventory();
        if(world.custody()==Custody.INCOMPLETE||slot!=null&&player==null||reference!=null&&ground==null){
            return new CoverageProof(Coverage.INCOMPLETE,"Player, entity or custody scope unavailable");
        }
        if(world.custody()==Custody.CONFLICTING){
            return new CoverageProof(Coverage.UNMATCHED,"Capture found conflicting custody evidence");
        }
        if(ground!=null&&ground.item()!=null&&ground.item().age()<0){
            return new CoverageProof(Coverage.INCOMPLETE,"Persisted entity lifetime unavailable");
        }
        if((slot==null)!=(player==null)||(reference==null)!=(ground==null)
                ||player!=null&&(!slot.actor().equals(player.player())||slot.slot()!=player.slot()||!items.inventory().equals(player.item()))
                ||ground!=null&&(!reference.equals(ground.reference())||!matchesGround(ground.item(),items.ground()))
                ||items.physical()!=Physical.BEFORE&&items.physical()!=Physical.AFTER){
            return new CoverageProof(Coverage.UNMATCHED,"Saved state differs from the exact owner observation");
        }
        String fingerprint=id+":"+database.source().sha256()+":"+world.source().sha256()
                +":"+(player==null?"none":player.source().sha256())+":"+(ground==null?"none":ground.source().sha256());
        return new CoverageProof(Coverage.MATCHED_COMPLETE,"Matched item save capture "+digest(fingerprint));
    }

    private static boolean matchesGround(GroundView saved,GroundView observed) {
        return saved==null?observed==null:saved.stillMatches(observed);
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        }catch(NoSuchAlgorithmException impossible){
            throw new IllegalStateException(impossible);
        }
    }
}
