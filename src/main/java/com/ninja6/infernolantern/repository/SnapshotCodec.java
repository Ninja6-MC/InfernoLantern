package com.ninja6.infernolantern.repository;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;
import static com.ninja6.infernolantern.repository.RepositoryRecords.*;

/** Versioned intent snapshots are data, never executable world mutations. */
final class SnapshotCodec {
    private SnapshotCodec() {}
    static byte[] encode(State state) throws IOException {
        var buffer = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(buffer)) {
            out.writeInt(0x494C5331); // ILS1
            Tower t = state.tower(); uuid(out,t.id()); string(out,t.variantId()); out.writeDouble(t.hp()); out.writeDouble(t.maximumHp()); string(out,t.form().name());
            out.writeLong(t.generation()); out.writeLong(t.deploymentRevision()); nullableUuid(out,t.controller());
            out.writeBoolean(t.position()!=null);
            if (t.position()!=null) { Position p=t.position(); uuid(out,p.world()); out.writeInt(p.x());out.writeInt(p.y());out.writeInt(p.z());string(out,p.orientation()); }
            out.writeBoolean(t.light());out.writeBoolean(t.defence());out.writeLong(t.expandAfter());out.writeLong(t.repairAfter());
            out.writeBoolean(state.ground()!=null);
            if(state.ground()!=null) { Ground g=state.ground();uuid(out,g.entity());uuid(out,g.world());out.writeInt(g.chunkX());out.writeInt(g.chunkZ());uuid(out,g.deliveryOperation());string(out,g.status()); }
            out.writeInt(state.components().size());
            for(Component c:state.components()) {
                string(out,c.id());string(out,c.kind().name());uuid(out,c.world());out.writeInt(c.x());out.writeInt(c.y());out.writeInt(c.z());
                out.writeBoolean(c.kind()==ComponentKind.BLOCK);
                if(c.kind()==ComponentKind.BLOCK){string(out,c.originalState());string(out,c.expectedState());}else{uuid(out,c.entity());string(out,c.entityType());}
            }
        }
        if(buffer.size()>MAX_PAYLOAD)throw new IOException("State snapshot exceeds technical byte bound");
        return buffer.toByteArray();
    }
    static State decode(byte[] bytes) throws IOException {
        if(bytes==null || bytes.length>MAX_PAYLOAD)throw new IOException("State snapshot size");
        try(var in=new DataInputStream(new ByteArrayInputStream(bytes))) {
            if(in.readInt()!=0x494C5331)throw new IOException("Unsupported state snapshot version");
            UUID id=uuid(in);String variant=string(in);double hp=in.readDouble(),max=in.readDouble();Form form=Form.valueOf(string(in));
            long generation=in.readLong(),revision=in.readLong();UUID controller=nullableUuid(in);
            Position position=in.readBoolean()?new Position(uuid(in),in.readInt(),in.readInt(),in.readInt(),string(in)):null;
            Tower t=new Tower(id,variant,hp,max,form,generation,revision,controller,position,in.readBoolean(),in.readBoolean(),in.readLong(),in.readLong());
            Ground ground=in.readBoolean()?new Ground(uuid(in),uuid(in),in.readInt(),in.readInt(),uuid(in),string(in)):null;
            int count=in.readInt();if(count<0||count>MAX_COMPONENTS)throw new IOException("Component count");
            var components=new ArrayList<Component>(count);
            for(int i=0;i<count;i++){
                String componentId=string(in);ComponentKind kind=ComponentKind.valueOf(string(in));UUID world=uuid(in);int x=in.readInt(),y=in.readInt(),z=in.readInt();
                if(in.readBoolean()!=(kind==ComponentKind.BLOCK))throw new IOException("Component type mismatch");
                components.add(kind==ComponentKind.BLOCK?new Component(componentId,kind,world,x,y,z,string(in),string(in),null,null):new Component(componentId,kind,world,x,y,z,null,null,uuid(in),string(in)));
            }
            if(in.read()!=-1)throw new IOException("Trailing state snapshot bytes");
            return new State(t,ground,components);
        } catch(IllegalArgumentException failure){throw new IOException("Malformed state snapshot",failure);}
    }
    private static void uuid(DataOutputStream out,UUID value)throws IOException{out.writeLong(value.getMostSignificantBits());out.writeLong(value.getLeastSignificantBits());}
    private static UUID uuid(DataInputStream in)throws IOException{return new UUID(in.readLong(),in.readLong());}
    private static void nullableUuid(DataOutputStream out,UUID value)throws IOException{out.writeBoolean(value!=null);if(value!=null)uuid(out,value);}
    private static UUID nullableUuid(DataInputStream in)throws IOException{return in.readBoolean()?uuid(in):null;}
    private static void string(DataOutputStream out,String value)throws IOException{byte[] bytes=value.getBytes(StandardCharsets.UTF_8);if(bytes.length>MAX_PAYLOAD)throw new IOException("String size");out.writeInt(bytes.length);out.write(bytes);}
    private static String string(DataInputStream in)throws IOException{int n=in.readInt();if(n<0||n>MAX_PAYLOAD||n>in.available())throw new IOException("String size");byte[] bytes=in.readNBytes(n);return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();}
}
