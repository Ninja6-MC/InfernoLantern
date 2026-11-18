package com.ninja6.infernolantern.recovery;

import com.ninja6.infernolantern.item.*;
import com.ninja6.infernolantern.repository.*;
import com.ninja6.infernolantern.scheduling.WorldScheduler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import static com.ninja6.infernolantern.repository.RepositoryRecords.*;
import static org.junit.jupiter.api.Assertions.*;

class RecoveryEnvelopeTest {
    final UUID world=UUID.randomUUID(),actor=UUID.randomUUID(),session=UUID.randomUUID();
    final WorldScheduler.Anchor anchor=new WorldScheduler.Anchor(world,0,0);
    Document raw(String action){return new Document(1,"action="+action+";actor="+actor+";session="+session+";slot=2;source=AQID");}
    @Test void typedRecipeRoundTripsAndPreservesRawReceiptEvidence(){
        Document original=raw("drop"),encoded=RecoveryEnvelope.item("item-drop",anchor,original);
        var context=RecoveryEnvelope.decode(encoded).orElseThrow();assertEquals(2,encoded.version());assertEquals(original,context.original());assertEquals(actor,context.inventory().actor());assertEquals(2,context.inventory().slot());assertArrayEquals(new byte[]{1,2,3},context.inventory().bytes().copy());assertEquals(original,RecoveryEnvelope.original(encoded));assertEquals(encoded,RecoveryEnvelope.encode(context));
    }
    @Test void alteredOrUnknownEnvelopesNeverBecomeRecognizedProof(){
        Document encoded=RecoveryEnvelope.item("item-drop",anchor,raw("drop"));byte[] bytes=Base64.getDecoder().decode(encoded.content());
        assertTrue(RecoveryEnvelope.decode(new Document(1,encoded.content())).isEmpty());assertTrue(RecoveryEnvelope.decode(new Document(3,encoded.content())).isEmpty());assertTrue(RecoveryEnvelope.decode(new Document(2,"not a valid base64 envelope")).isEmpty());
        byte[] trailing=Arrays.copyOf(bytes,bytes.length+1);assertTrue(RecoveryEnvelope.decode(new Document(2,Base64.getEncoder().encodeToString(trailing))).isEmpty());bytes[0]=0;assertTrue(RecoveryEnvelope.decode(new Document(2,Base64.getEncoder().encodeToString(bytes))).isEmpty());
    }
    @Test void malformedOrMismatchedSourceAndExternalLossStayAdministrative(){
        assertEquals(RecoveryEnvelope.Profile.ADMIN,RecoveryEnvelope.decode(RecoveryEnvelope.item("item-drop",anchor,raw("pickup"))).orElseThrow().profile());
        assertEquals(RecoveryEnvelope.Profile.ADMIN,RecoveryEnvelope.decode(RecoveryEnvelope.item("item-drop",anchor,new Document(1,"opaque source"))).orElseThrow().profile());
        assertEquals(RecoveryEnvelope.Profile.ADMIN,RecoveryEnvelope.decode(RecoveryEnvelope.item("item-loss",anchor,new Document(1,"external-removal-DEATH"))).orElseThrow().profile());
        assertEquals(RecoveryEnvelope.Profile.INTENTIONAL_LOSS,RecoveryEnvelope.decode(RecoveryEnvelope.item("item-loss",anchor,new Document(1,"despawn"))).orElseThrow().profile());
        var wrong=new RecoveryEnvelope.Inventory(UUID.randomUUID(),2,new ResourceBytes(new byte[]{1,2,3}),null);
        assertThrows(IllegalArgumentException.class,()->new RecoveryEnvelope.Context("item-drop",anchor,RecoveryEnvelope.Profile.INVENTORY,wrong,raw("drop")));
    }
    @Test void aRecipeCannotSubstituteAnotherAnchorOrAlterAuthoritativeHp(){
        UUID tower=UUID.randomUUID(),op=UUID.randomUUID();Tower before=new Tower(tower,"fixture",25,100,Form.PORTABLE,1,0,null,null,false,true,300,400);
        Tower after=new Tower(tower,"fixture",25,100,Form.GROUND,2,0,null,null,false,true,300,400);Ground ground=new Ground(UUID.randomUUID(),world,0,0,op,"active");
        var context=RecoveryEnvelope.decode(RecoveryEnvelope.item("item-drop",anchor,raw("drop"))).orElseThrow();
        Operation operation=new Operation(new Intent(op,tower,0,1,0,"item-drop",new State(after,ground,List.of()),RecoveryEnvelope.encode(context)),new State(before,null,List.of()),Phase.INTENT,0,"intent");assertTrue(RecoveryEnvelope.supports(operation,context));
        var moved=new RecoveryEnvelope.Context(context.kind(),new WorldScheduler.Anchor(world,1,0),context.profile(),context.inventory(),context.original());assertFalse(RecoveryEnvelope.supports(operation,moved));
        Tower forged=new Tower(tower,"fixture",100,100,Form.GROUND,2,0,null,null,false,true,300,400);
        assertFalse(RecoveryEnvelope.supports(new Operation(new Intent(op,tower,0,1,0,"item-drop",new State(forged,ground,List.of()),RecoveryEnvelope.encode(context)),operation.before(),Phase.INTENT,0,"intent"),context));
    }
    @Test void actualFixtureProducerEmitsTypedDeliveryAndAdministrativeActivationReview(@TempDir Path dir)throws Exception{
        var repository=SqliteRepository.open(dir.resolve("fixture.db"),64).get(10,TimeUnit.SECONDS);
        try{
            repository.createVariant(new Variant(FixtureDelivery.VARIANT,1,VariantStatus.ACTIVE,new Document(1,"generic fixture"),0)).get();
            UUID operation=UUID.randomUUID(),tower=UUID.randomUUID();
            var details=new Document(1,"fixture recipient="+actor+";session="+session+";slot=2;staged=AQID");
            var delivery=new FixtureDelivery(repository,new WorldScheduler(){public <T>CompletableFuture<T> supply(Anchor a,java.util.function.Supplier<T> action){return CompletableFuture.completedFuture(action.get());}});
            try{delivery.issue(new FixtureDelivery.Request(tower,operation,anchor,details),new FixtureDelivery.Effects(){public boolean stage(){return true;}public boolean verify(){return true;}public boolean activate(){return false;}}).get(10,TimeUnit.SECONDS);
                Operation original=repository.operation(operation).get().orElseThrow();var context=RecoveryEnvelope.decode(original.intent().effects()).orElseThrow();assertEquals(RecoveryEnvelope.Profile.INVENTORY,context.profile());assertEquals(operation,context.inventory().deliveryOperation());assertTrue(RecoveryEnvelope.supports(original,context));
                Operation review=repository.pendingOperations(null,10).get().getFirst();assertEquals(RecoveryEnvelope.Profile.ADMIN,RecoveryEnvelope.decode(review.intent().effects()).orElseThrow().profile());
            }finally{delivery.close();}
        }finally{repository.closeAsync().get(10,TimeUnit.SECONDS);}
    }
}
