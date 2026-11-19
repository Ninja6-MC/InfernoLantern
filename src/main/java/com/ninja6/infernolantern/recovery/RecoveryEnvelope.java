package com.ninja6.infernolantern.recovery;

import com.ninja6.infernolantern.repository.RepositoryRecords.*;
import com.ninja6.infernolantern.scheduling.WorldScheduler.Anchor;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.ninja6.infernolantern.repository.RepositoryRecords.*;

/** Named observation recipe, not proof that independent Minecraft saves agree. */
public final class RecoveryEnvelope {
    private RecoveryEnvelope() {}
    public enum Profile { INVENTORY, STRUCTURE, INTENTIONAL_LOSS, ADMIN }
    public record Inventory(UUID actor,int slot,ResourceBytes bytes,UUID deliveryOperation) {
        public Inventory { Objects.requireNonNull(actor);Objects.requireNonNull(bytes);if(slot<0||slot>255||bytes.copy().length==0)throw new IllegalArgumentException("Inventory evidence"); }
    }
    public record Context(String kind,Anchor anchor,Profile profile,Inventory inventory,Document original) {
        public Context {
            Objects.requireNonNull(kind);Objects.requireNonNull(anchor);Objects.requireNonNull(profile);Objects.requireNonNull(original);
            if(!KINDS.contains(kind)||original.version()!=1)throw new IllegalArgumentException("Recovery recipe");
            if(profile==Profile.INVENTORY&&inventory==null)throw new IllegalArgumentException("Missing source evidence");
            if(profile==Profile.INVENTORY&&!Set.of("item-drop","item-pickup","fixture-item-delivery").contains(kind)
                    ||profile==Profile.STRUCTURE&&(!kind.startsWith("structure-")||kind.equals("structure-activation-review")||timedStructureExit(kind))
                    ||profile==Profile.STRUCTURE&&kind.equals("structure-place")&&inventory==null
                    ||profile==Profile.INTENTIONAL_LOSS&&(!kind.equals("item-loss")||!intentional(original)))throw new IllegalArgumentException("Recipe/profile mismatch");
            if(profile==Profile.INVENTORY){
                Inventory parsed=RecoveryEnvelope.inventory(original,kind.equals("fixture-item-delivery"),inventory.deliveryOperation());
                if(!inventory.equals(parsed)||!kind.equals("fixture-item-delivery")&&!original.content().startsWith("action="+kind.substring(5)+";"))throw new IllegalArgumentException("Source recipe mismatch");
            }
        }
    }
    private static final Set<String> KINDS=Set.of("item-drop","item-pickup","item-loss","item-place","fixture-item-delivery","fixture-activation-review",
            "structure-place","structure-expand","structure-compact",STRUCTURE_DEFEAT,STRUCTURE_EXIT_COMPACT,STRUCTURE_EXIT_RECOVER,"structure-break","structure-admin_recover","structure-activation-review");
    public static Document item(String kind,Anchor anchor,Document original) {
        Inventory source=inventory(original,false,null);
        Profile profile=(kind.equals("item-drop")||kind.equals("item-pickup"))&&source!=null&&original.content().startsWith("action="+kind.substring(5)+";")?Profile.INVENTORY:Profile.ADMIN;
        if(kind.equals("item-loss")&&intentional(original))profile=Profile.INTENTIONAL_LOSS;
        return encode(new Context(kind,anchor,profile,source,original));
    }
    public static Document fixture(String kind,Anchor anchor,Document original,UUID delivery) {
        Inventory source=inventory(original,true,delivery);
        return encode(new Context(kind,anchor,kind.equals("fixture-item-delivery")&&source!=null?Profile.INVENTORY:Profile.ADMIN,source,original));
    }
    public static Document structure(String kind,Anchor anchor,Document original,Inventory source) {
        Profile profile=timedStructureExit(kind)||kind.equals("structure-activation-review")||kind.equals("structure-place")&&source==null?Profile.ADMIN:Profile.STRUCTURE;
        return encode(new Context(kind,anchor,profile,source,original));
    }
    public static Document administrative(String kind,Anchor anchor,Document original){return encode(new Context(kind,anchor,Profile.ADMIN,null,original));}
    /** Original caller evidence remains available for immutable receipt matching. */
    public static Document original(Document document) {return decode(document).map(Context::original).orElse(document);}
    private static boolean intentional(Document original){return original.version()==1&&(original.content().equals("despawn")||original.content().matches("damage-[A-Z_]+"));}
    /** A supported envelope also has to describe the actual journal transition and owner anchor. */
    public static boolean supports(Operation op,Context c){
        Tower a=op.before().tower(),b=op.intent().after().tower();
        if(!c.kind().equals(op.intent().kind())||c.profile()==Profile.ADMIN||!a.id().equals(b.id())||!a.variantId().equals(b.variantId())
                ||a.hp()!=b.hp()||a.maximumHp()!=b.maximumHp()||a.light()!=b.light()||a.defence()!=b.defence()||a.repairAfter()!=b.repairAfter())return false;
        for(State s:List.of(op.before(),op.intent().after())){
            Position p=s.tower().position();Ground g=s.ground();
            if(p!=null&&(!p.world().equals(c.anchor().world())||(p.x()>>4)!=c.anchor().chunkX()||(p.z()>>4)!=c.anchor().chunkZ()))return false;
            if(g!=null&&(!g.world().equals(c.anchor().world())||g.chunkX()!=c.anchor().chunkX()||g.chunkZ()!=c.anchor().chunkZ()))return false;
        }
        boolean nextGeneration=a.generation()!=Long.MAX_VALUE&&b.generation()==a.generation()+1;
        boolean nextRevision=a.deploymentRevision()!=Long.MAX_VALUE&&b.deploymentRevision()==a.deploymentRevision()+1;
        return switch(c.kind()){
            case "item-drop"->a.form()==Form.PORTABLE&&b.form()==Form.GROUND&&nextGeneration&&a.deploymentRevision()==b.deploymentRevision()&&a.expandAfter()==b.expandAfter()&&op.intent().after().ground().deliveryOperation().equals(op.intent().id());
            case "item-pickup"->a.form()==Form.GROUND&&b.form()==Form.PORTABLE&&nextGeneration&&a.deploymentRevision()==b.deploymentRevision()&&a.expandAfter()==b.expandAfter();
            case "item-loss"->a.form()==Form.GROUND&&b.form()==Form.LOST&&nextGeneration&&a.deploymentRevision()==b.deploymentRevision()&&a.expandAfter()==b.expandAfter();
            case "fixture-item-delivery"->a.form()==Form.PORTABLE&&b.form()==Form.PORTABLE&&a.variantId().equals("prototype.item-fixture")&&a.generation()==0&&b.generation()==1&&a.hp()==0&&a.maximumHp()==100&&a.controller()==null&&a.deploymentRevision()==0&&b.deploymentRevision()==0&&a.expandAfter()==b.expandAfter()&&op.intent().id().equals(c.inventory().deliveryOperation());
            case "structure-place"->a.form()==Form.PORTABLE&&b.form()==Form.COMPACT&&nextGeneration&&nextRevision&&a.expandAfter()==b.expandAfter();
            case "structure-expand"->a.form()==Form.COMPACT&&b.form()==Form.EXPANDED&&a.generation()==b.generation()&&nextRevision&&a.expandAfter()==b.expandAfter();
            case "structure-compact"->a.form()==Form.EXPANDED&&b.form()==Form.COMPACT&&a.generation()==b.generation()&&nextRevision;
            case "structure-break"->a.form()==Form.COMPACT&&b.form()==Form.GROUND&&nextGeneration&&nextRevision&&a.expandAfter()==b.expandAfter()&&op.intent().after().ground().deliveryOperation().equals(op.intent().id());
            case "structure-admin_recover"->a.form()==Form.EXPANDED&&b.form()==Form.GROUND&&nextGeneration&&nextRevision&&op.intent().after().ground().deliveryOperation().equals(op.intent().id());
            default->false;
        };
    }
    private static Inventory inventory(Document original,boolean fixture,UUID delivery) {
        if(original.version()!=1)return null;
        try {
            String[] p=original.content().split(";",-1);
            if(fixture){
                if(p.length!=4||!p[0].startsWith("fixture recipient=")||!p[1].startsWith("session=")||!p[2].startsWith("slot=")||!p[3].startsWith("staged="))return null;
                UUID actor=UUID.fromString(p[0].substring(18));UUID.fromString(p[1].substring(8));
                return new Inventory(actor,Integer.parseInt(p[2].substring(5)),new ResourceBytes(Base64.getDecoder().decode(p[3].substring(7))),delivery);
            }
            if(p.length!=5||!Set.of("action=drop","action=pickup").contains(p[0])||!p[1].startsWith("actor=")||!p[2].startsWith("session=")||!p[3].startsWith("slot=")||!p[4].startsWith("source="))return null;
            UUID actor=UUID.fromString(p[1].substring(6));UUID.fromString(p[2].substring(8));
            return new Inventory(actor,Integer.parseInt(p[3].substring(5)),new ResourceBytes(Base64.getDecoder().decode(p[4].substring(7))),null);
        } catch(RuntimeException malformed){return null;}
    }
    public static Document encode(Context c) {
        try {
            var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);
            out.writeInt(0x494C5231);text(out,c.kind());uuid(out,c.anchor().world());out.writeInt(c.anchor().chunkX());out.writeInt(c.anchor().chunkZ());out.writeInt(c.profile().ordinal());
            out.writeBoolean(c.inventory()!=null);
            if(c.inventory()!=null){Inventory s=c.inventory();uuid(out,s.actor());out.writeInt(s.slot());byte[] b=s.bytes().copy();out.writeInt(b.length);out.write(b);out.writeBoolean(s.deliveryOperation()!=null);if(s.deliveryOperation()!=null)uuid(out,s.deliveryOperation());}
            text(out,c.original().content());out.flush();return new Document(2,Base64.getEncoder().encodeToString(bytes.toByteArray()));
        }catch(IOException impossible){throw new UncheckedIOException(impossible);}
    }
    public static Optional<Context> decode(Document d) {
        if(d.version()!=2)return Optional.empty();
        try {
            byte[] decoded=Base64.getDecoder().decode(d.content());if(decoded.length>1_048_576)return Optional.empty();
            var in=new DataInputStream(new ByteArrayInputStream(decoded));if(in.readInt()!=0x494C5231)return Optional.empty();
            String kind=text(in);Anchor anchor=new Anchor(uuid(in),in.readInt(),in.readInt());int p=in.readInt();if(p<0||p>=Profile.values().length)return Optional.empty();
            Inventory source=null;
            if(in.readBoolean()){UUID actor=uuid(in);int slot=in.readInt(),n=in.readInt();if(n<1||n>1_048_576||n>in.available())return Optional.empty();byte[] b=in.readNBytes(n);source=new Inventory(actor,slot,new ResourceBytes(b),in.readBoolean()?uuid(in):null);}
            Context c=new Context(kind,anchor,Profile.values()[p],source,new Document(1,text(in)));if(in.available()!=0)return Optional.empty();return Optional.of(c);
        }catch(IOException|RuntimeException malformed){return Optional.empty();}
    }
    private static void uuid(DataOutputStream out,UUID id)throws IOException{out.writeLong(id.getMostSignificantBits());out.writeLong(id.getLeastSignificantBits());}
    private static UUID uuid(DataInputStream in)throws IOException{return new UUID(in.readLong(),in.readLong());}
    private static void text(DataOutputStream out,String value)throws IOException{byte[] b=value.getBytes(StandardCharsets.UTF_8);out.writeInt(b.length);out.write(b);}
    private static String text(DataInputStream in)throws IOException{int n=in.readInt();if(n<1||n>1_048_576||n>in.available())throw new IOException("Recovery text bounds");byte[] b=in.readNBytes(n);return StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(b)).toString();}
}
