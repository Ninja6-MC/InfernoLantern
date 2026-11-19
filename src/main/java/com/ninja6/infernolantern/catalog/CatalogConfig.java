package com.ninja6.infernolantern.catalog;

import java.util.*;

/** Fully validated immutable settings for the catalog module; no implicit combat/repair balance. */
public final class CatalogConfig {
    public enum Action { PLACE, EXPAND, LIGHT, DEFENCE }
    public enum Dimension { OVERWORLD, NETHER, END, CUSTOM, UNKNOWN }
    public record WorldRef(UUID id,String name,Dimension dimension,boolean customSupported) {
        public WorldRef {Objects.requireNonNull(id);text(name,256);Objects.requireNonNull(dimension);}
    }
    public record Selector(UUID id,String name) {
        public Selector {if((id==null)==(name==null))throw new IllegalArgumentException("One selector identity required");if(name!=null)text(name,256);}
        public static Selector parse(String value){
            if(value.startsWith("uuid:")){String raw=value.substring(5);UUID id=UUID.fromString(raw);if(!id.toString().equalsIgnoreCase(raw))throw new IllegalArgumentException("Canonical UUID selector required");return new Selector(id,null);}
            if(value.startsWith("name:"))return new Selector(null,value.substring(5));
            throw new IllegalArgumentException("World selector requires uuid: or name:");
        }
        public boolean matches(WorldRef world){return id!=null?id.equals(world.id()):name.equals(world.name());}
        public String encoded(){return id!=null?"uuid:"+id:"name:"+name;}
    }
    /** null allowlist means absent; an empty configured allowlist permits none. */
    public record WorldRule(List<Selector> allowWorlds,List<Selector> denyWorlds,Set<Dimension> allowDimensions) {
        public WorldRule {allowWorlds=allowWorlds==null?null:List.copyOf(allowWorlds);denyWorlds=List.copyOf(denyWorlds);allowDimensions=allowDimensions==null?null:Set.copyOf(allowDimensions);if(allowDimensions!=null&&allowDimensions.contains(Dimension.UNKNOWN))throw new IllegalArgumentException("Unknown dimension filter");}
        public static WorldRule unrestricted(){return new WorldRule(null,List.of(),null);}
        public boolean allows(WorldRef world){
            if(denyWorlds.stream().anyMatch(s->s.matches(world)))return false;
            if(allowWorlds!=null&&allowWorlds.stream().noneMatch(s->s.matches(world)))return false;
            return allowDimensions==null||(world.dimension()!=Dimension.UNKNOWN&&(world.dimension()!=Dimension.CUSTOM||world.customSupported())&&allowDimensions.contains(world.dimension()));
        }
    }
    public record Geometry(String compactModel,String expandedModel,int width,int height,int depth,int lightX,int lightY,int lightZ) {
        public Geometry {resource(compactModel);resource(expandedModel);if(width<1||height<1||depth<1||width>4095||height>4095||depth>4095||(long)width*height*depth>4095||lightX<0||lightX>=width||lightY<0||lightY>=height||lightZ<0||lightZ>=depth)throw new IllegalArgumentException("Declared geometry/light-cell bounds");}
    }
    public record Definition(String id,boolean lightAvailable,boolean defenceAvailable,boolean defaultLight,boolean defaultDefence,
                             int lightLevel,double maximumHp,double expansionPercent,long expansionCooldownMillis,String permission,
                             Geometry geometry,Map<Action,WorldRule> worlds) {
        public Definition {
            identifier(id);if(id.startsWith("prototype."))throw new IllegalArgumentException("Reserved fixture variant");
            if(lightLevel<0||lightLevel>15||!Double.isFinite(maximumHp)||maximumHp<=0||!Double.isFinite(expansionPercent)||expansionPercent<=0||expansionPercent>100||expansionCooldownMillis<0)throw new IllegalArgumentException("Variant numeric bounds");
            if(permission!=null){text(permission,256);if(!permission.matches("[a-z0-9][a-z0-9_.:-]{0,255}"))throw new IllegalArgumentException("Permission syntax");}
            Objects.requireNonNull(geometry);worlds=Map.copyOf(worlds);if(!worlds.keySet().equals(EnumSet.allOf(Action.class)))throw new IllegalArgumentException("Complete action policies required");
        }
    }
    public record Candidate(boolean defaultLightAvailable,boolean defaultDefenceAvailable,Map<String,Definition> variants) {
        public Candidate {variants=Collections.unmodifiableMap(new TreeMap<>(variants));if(variants.size()>1024)throw new IllegalArgumentException("Catalog size");for(var pair:variants.entrySet())if(!pair.getKey().equals(pair.getValue().id()))throw new IllegalArgumentException("Variant identity mismatch");}
        public static Candidate empty(){return new Candidate(true,true,Map.of());}
    }
    private CatalogConfig(){}
    public static Candidate parse(Map<String,?> input){
        Map<String,?> root=map(input);keys(root,"schema","capabilities","variants");
        if(integer(required(root,"schema"))!=1)throw new IllegalArgumentException("Unsupported catalog schema");
        Map<String,?> defaults=map(optional(root,"capabilities"));keys(defaults,"light","defence");
        boolean light=bool(defaults,"light",true),defence=bool(defaults,"defence",true);
        Map<String,?> variants=map(required(root,"variants"));var result=new TreeMap<String,Definition>();
        for(var entry:variants.entrySet()){
            String id=entry.getKey();Map<String,?> v=map(entry.getValue());
            keys(v,"capabilities","player-switches","light-level","maximum-hp","expansion-hp-percent","expansion-cooldown-ms","permission","geometry","world-rules");
            Map<String,?> c=map(optional(v,"capabilities"));keys(c,"light","defence");
            Map<String,?> switches=map(required(v,"player-switches"));keys(switches,"light","defence");
            boolean defaultLight=booleanValue(required(switches,"light")),defaultDefence=booleanValue(required(switches,"defence"));
            Map<String,?> g=map(required(v,"geometry"));keys(g,"compact-model","expanded-model","width","height","depth","light-x","light-y","light-z");
            Geometry geometry=new Geometry(string(required(g,"compact-model")),string(required(g,"expanded-model")),integer(required(g,"width")),integer(required(g,"height")),integer(required(g,"depth")),integer(required(g,"light-x")),integer(required(g,"light-y")),integer(required(g,"light-z")));
            Map<String,?> rules=map(optional(v,"world-rules"));keys(rules,"place","expand","light","defence");var worlds=new EnumMap<Action,WorldRule>(Action.class);
            for(Action action:Action.values())worlds.put(action,rule(map(optional(rules,action.name().toLowerCase(Locale.ROOT)))));
            result.put(id,new Definition(id,bool(c,"light",light),bool(c,"defence",defence),defaultLight,defaultDefence,integer(required(v,"light-level")),number(required(v,"maximum-hp")),v.containsKey("expansion-hp-percent")?number(v.get("expansion-hp-percent")):50,nonnegativeLong(required(v,"expansion-cooldown-ms")),v.containsKey("permission")?string(v.get("permission")):null,geometry,worlds));
        }
        return new Candidate(light,defence,result);
    }
    private static WorldRule rule(Map<String,?> map){keys(map,"allow-worlds","deny-worlds","allow-dimensions");
        List<Selector> allow=map.containsKey("allow-worlds")?selectors(map.get("allow-worlds")):null;
        List<Selector> deny=map.containsKey("deny-worlds")?selectors(map.get("deny-worlds")):List.of();Set<Dimension> dimensions=null;
        if(map.containsKey("allow-dimensions")){dimensions=EnumSet.noneOf(Dimension.class);for(Object value:list(map.get("allow-dimensions"))){String category=string(value);Dimension dimension=switch(category){case "overworld"->Dimension.OVERWORLD;case "nether"->Dimension.NETHER;case "end"->Dimension.END;case "custom"->Dimension.CUSTOM;default->throw new IllegalArgumentException("Unknown dimension category");};if(!dimensions.add(dimension))throw new IllegalArgumentException("Duplicate dimension selector");}}
        return new WorldRule(allow,deny,dimensions);
    }
    private static List<Selector> selectors(Object value){var result=new ArrayList<Selector>();for(Object entry:list(value)){Selector selector=Selector.parse(string(entry));if(result.contains(selector))throw new IllegalArgumentException("Duplicate world selector");result.add(selector);}return List.copyOf(result);}
    private static List<?> list(Object value){if(!(value instanceof List<?> list)||list.size()>1024)throw new IllegalArgumentException("Bounded list required");return list;}
    private static Object optional(Map<String,?> map,String key){return map.containsKey(key)?map.get(key):Map.of();}
    private static Map<String,?> map(Object value){if(!(value instanceof Map<?,?> map)||map.size()>4096)throw new IllegalArgumentException("Object required");var result=new LinkedHashMap<String,Object>();for(var entry:map.entrySet()){if(!(entry.getKey() instanceof String key))throw new IllegalArgumentException("String key required");result.put(key,entry.getValue());}return result;}
    private static void keys(Map<String,?> map,String... allowed){Set<String> keys=Set.of(allowed);for(String key:map.keySet())if(!keys.contains(key))throw new IllegalArgumentException("Unknown configuration key: "+key);}
    private static Object required(Map<String,?> map,String key){if(!map.containsKey(key)||map.get(key)==null)throw new IllegalArgumentException("Required setting: "+key);return map.get(key);}
    private static boolean bool(Map<String,?> map,String key,boolean fallback){return map.containsKey(key)?booleanValue(map.get(key)):fallback;}
    private static boolean booleanValue(Object value){if(!(value instanceof Boolean b))throw new IllegalArgumentException("Boolean required");return b;}
    private static String string(Object value){if(!(value instanceof String s))throw new IllegalArgumentException("String required");return s;}
    private static double number(Object value){if(!(value instanceof Number n)||!Double.isFinite(n.doubleValue()))throw new IllegalArgumentException("Finite number required");return n.doubleValue();}
    private static long nonnegativeLong(Object value){if(!(value instanceof Byte||value instanceof Short||value instanceof Integer||value instanceof Long))throw new IllegalArgumentException("Integer required");long n=((Number)value).longValue();if(n<0)throw new IllegalArgumentException("Nonnegative duration required");return n;}
    private static int integer(Object value){long n=nonnegativeLong(value);if(n>Integer.MAX_VALUE)throw new IllegalArgumentException("Integer overflow");return (int)n;}
    static void identifier(String value){if(value==null||!value.matches("[a-z0-9][a-z0-9_.:-]{0,127}"))throw new IllegalArgumentException("Variant ID");}
    static void resource(String value){if(value==null||!value.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")||value.length()>256||value.contains(".."))throw new IllegalArgumentException("Resource identifier");}
    static void text(String value,int max){if(value==null||value.isBlank()||value.length()>max||value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>max||!java.nio.charset.StandardCharsets.UTF_8.newEncoder().canEncode(value))throw new IllegalArgumentException("Bounded text required");}
}
