package com.ninja6.infernolantern.catalog;

import com.ninja6.infernolantern.repository.RepositoryRecords.Document;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.ninja6.infernolantern.catalog.CatalogConfig.*;

/** Bounded deterministic catalog persistence; never Java object deserialization. */
public final class CatalogCodec {
    private CatalogCodec(){}
    public static Document candidate(Candidate candidate){return encode(1,out->{out.writeBoolean(candidate.defaultLightAvailable());out.writeBoolean(candidate.defaultDefenceAvailable());out.writeInt(candidate.variants().size());for(Definition d:candidate.variants().values())writeDefinition(out,d);});}
    public static Document definition(Definition definition){return encode(2,out->writeDefinition(out,definition));}
    public static Candidate candidate(Document document){Candidate result=decode(document,1,in->{boolean light=in.readBoolean(),defence=in.readBoolean();int n=count(in,1024);var variants=new TreeMap<String,Definition>();for(int i=0;i<n;i++){Definition d=readDefinition(in);if(variants.put(d.id(),d)!=null)throw new IOException("Duplicate variant");}return new Candidate(light,defence,variants);});if(!candidate(result).equals(document))throw new IllegalArgumentException("Noncanonical catalog");return result;}
    public static Definition definition(Document document){Definition result=decode(document,2,CatalogCodec::readDefinition);if(!definition(result).equals(document))throw new IllegalArgumentException("Noncanonical definition");return result;}
    private interface Writer{void write(DataOutputStream out)throws IOException;}
    private interface Reader<T>{T read(DataInputStream in)throws IOException;}
    private static Document encode(int kind,Writer writer){try{var bytes=new ByteArrayOutputStream();try(var out=new DataOutputStream(bytes)){out.writeInt(0x494C4331);out.writeByte(kind);writer.write(out);}return new Document(1,"catalog-v1:"+Base64.getEncoder().encodeToString(bytes.toByteArray()));}catch(IOException impossible){throw new IllegalStateException(impossible);}}
    private static <T>T decode(Document document,int kind,Reader<T> reader){
        try{if(document.version()!=1||!document.content().startsWith("catalog-v1:"))throw new IOException("Unknown catalog payload");byte[] bytes=Base64.getDecoder().decode(document.content().substring(11));
            try(var in=new DataInputStream(new ByteArrayInputStream(bytes))){if(in.readInt()!=0x494C4331||in.readUnsignedByte()!=kind)throw new IOException("Catalog payload type");T result=reader.read(in);if(in.read()!=-1)throw new IOException("Trailing catalog data");return result;}
        }catch(IOException|RuntimeException invalid){throw new IllegalArgumentException("Invalid catalog payload; preserve definition",invalid);}
    }
    private static void string(DataOutputStream out,String value)throws IOException{byte[] bytes=value.getBytes(StandardCharsets.UTF_8);out.writeInt(bytes.length);out.write(bytes);}
    private static String string(DataInputStream in,int max)throws IOException{int n=count(in,max);byte[] bytes=in.readNBytes(n);if(bytes.length!=n)throw new EOFException();String value=new String(bytes,StandardCharsets.UTF_8);if(!Arrays.equals(value.getBytes(StandardCharsets.UTF_8),bytes))throw new IOException("Invalid UTF-8");return value;}
    private static int count(DataInputStream in,int max)throws IOException{int n=in.readInt();if(n<0||n>max)throw new IOException("Catalog count bounds");return n;}
    private static void writeDefinition(DataOutputStream out,Definition d)throws IOException{
        string(out,d.id());out.writeBoolean(d.lightAvailable());out.writeBoolean(d.defenceAvailable());out.writeBoolean(d.defaultLight());out.writeBoolean(d.defaultDefence());out.writeInt(d.lightLevel());out.writeDouble(d.maximumHp());out.writeDouble(d.expansionPercent());out.writeLong(d.expansionCooldownMillis());out.writeBoolean(d.permission()!=null);if(d.permission()!=null)string(out,d.permission());
        Geometry g=d.geometry();string(out,g.compactModel());string(out,g.expandedModel());for(int value:new int[]{g.width(),g.height(),g.depth(),g.lightX(),g.lightY(),g.lightZ()})out.writeInt(value);
        for(Action action:Action.values()){WorldRule r=d.worlds().get(action);selectors(out,r.allowWorlds());selectors(out,r.denyWorlds());out.writeBoolean(r.allowDimensions()!=null);if(r.allowDimensions()!=null){out.writeInt(r.allowDimensions().size());for(Dimension dimension:Dimension.values())if(r.allowDimensions().contains(dimension))string(out,dimension.name());}}
    }
    private static Definition readDefinition(DataInputStream in)throws IOException{
        String id=string(in,128);boolean light=in.readBoolean(),defence=in.readBoolean(),defaultLight=in.readBoolean(),defaultDefence=in.readBoolean();int level=in.readInt();double hp=in.readDouble(),threshold=in.readDouble();long cooldown=in.readLong();String permission=in.readBoolean()?string(in,256):null;
        Geometry geometry=new Geometry(string(in,256),string(in,256),in.readInt(),in.readInt(),in.readInt(),in.readInt(),in.readInt(),in.readInt());var worlds=new EnumMap<Action,WorldRule>(Action.class);
        for(Action action:Action.values()){List<Selector> allow=selectors(in),deny=selectors(in);if(deny==null)throw new IOException("Missing deny list");Set<Dimension> dimensions=null;if(in.readBoolean()){int n=count(in,4);dimensions=EnumSet.noneOf(Dimension.class);for(int i=0;i<n;i++)if(!dimensions.add(Dimension.valueOf(string(in,16))))throw new IOException("Duplicate dimension");}worlds.put(action,new WorldRule(allow,deny,dimensions));}
        return new Definition(id,light,defence,defaultLight,defaultDefence,level,hp,threshold,cooldown,permission,geometry,worlds);
    }
    private static void selectors(DataOutputStream out,List<Selector> values)throws IOException{out.writeBoolean(values!=null);if(values!=null){out.writeInt(values.size());for(Selector value:values)string(out,value.encoded());}}
    private static List<Selector> selectors(DataInputStream in)throws IOException{if(!in.readBoolean())return null;int n=count(in,1024);var values=new ArrayList<Selector>();for(int i=0;i<n;i++){Selector value=Selector.parse(string(in,512));if(values.contains(value))throw new IOException("Duplicate selector");values.add(value);}return values;}
}
