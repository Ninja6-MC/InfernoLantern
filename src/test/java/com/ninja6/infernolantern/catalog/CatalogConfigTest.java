package com.ninja6.infernolantern.catalog;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.ninja6.infernolantern.catalog.CatalogConfig.*;
import static com.ninja6.infernolantern.catalog.CatalogTestSupport.*;

class CatalogConfigTest {
    @Test void defaultsOverridesAndDurableCodecsAreImmutable(){
        var lightOnly=variant();lightOnly.put("capabilities",Map.of("defence",false));var config=config(Map.of("one",lightOnly,"two",variant()));config.put("capabilities",Map.of("light",false));
        Candidate candidate=CatalogConfig.parse(config);assertFalse(candidate.variants().get("one").lightAvailable());assertFalse(candidate.variants().get("one").defenceAvailable());assertTrue(candidate.variants().get("two").defenceAvailable());assertEquals(50,candidate.variants().get("two").expansionPercent());
        assertEquals(candidate,CatalogCodec.candidate(CatalogCodec.candidate(candidate)));for(Definition d:candidate.variants().values())assertEquals(d,CatalogCodec.definition(CatalogCodec.definition(d)));
        assertThrows(UnsupportedOperationException.class,()->candidate.variants().clear());lightOnly.clear();assertEquals(2,candidate.variants().size());
    }
    @Test void selectorContractDistinguishesAbsentEmptyDenyAndIntersection(){
        WorldRef here=world();Selector byId=Selector.parse("uuid:"+here.id()),byName=Selector.parse("name:"+here.name());
        assertTrue(WorldRule.unrestricted().allows(here));assertFalse(new WorldRule(List.of(),List.of(),null).allows(here));
        assertFalse(new WorldRule(List.of(byId),List.of(byName),null).allows(here));assertFalse(new WorldRule(List.of(byId),List.of(),Set.of(Dimension.NETHER)).allows(here));
        assertTrue(new WorldRule(List.of(byId),List.of(),Set.of(Dimension.OVERWORLD)).allows(here));
        var renamed=new WorldRef(here.id(),"renamed-world",Dimension.OVERWORLD,false);assertTrue(byId.matches(renamed));assertFalse(byName.matches(renamed));
        var replacement=new WorldRef(UUID.randomUUID(),here.name(),Dimension.OVERWORLD,false);assertFalse(byId.matches(replacement));assertTrue(byName.matches(replacement));
        assertFalse(new WorldRule(null,List.of(),Set.of(Dimension.CUSTOM)).allows(new WorldRef(here.id(),here.name(),Dimension.CUSTOM,false)));
        assertTrue(new WorldRule(null,List.of(),Set.of(Dimension.CUSTOM)).allows(new WorldRef(here.id(),here.name(),Dimension.CUSTOM,true)));
        assertFalse(new WorldRule(null,List.of(),Set.of(Dimension.OVERWORLD)).allows(new WorldRef(here.id(),here.name(),Dimension.UNKNOWN,false)));
    }
    @Test void parserPreservesExplicitEmptyAndPerActionPolicies(){
        var v=variant();v.put("world-rules",Map.of("place",Map.of("allow-worlds",List.of()),"light",Map.of("allow-worlds",List.of("name:"+world().name()),"deny-worlds",List.of("uuid:"+UUID.randomUUID()),"allow-dimensions",List.of("overworld"))));
        Definition d=CatalogConfig.parse(config(Map.of("test",v))).variants().get("test");assertFalse(d.worlds().get(Action.PLACE).allows(world()));assertTrue(d.worlds().get(Action.EXPAND).allows(world()));assertTrue(d.worlds().get(Action.LIGHT).allows(world()));
        assertEquals(d,CatalogCodec.definition(CatalogCodec.definition(d)));
    }
    @Test void strictInputRejectsUnknownInvalidOrInventedBalance(){
        for(Object bad:List.of(Double.NaN,Double.POSITIVE_INFINITY,0,-1)){var v=variant();v.put("maximum-hp",bad);assertThrows(IllegalArgumentException.class,()->CatalogConfig.parse(config(Map.of("test",v))));}
        for(Object bad:List.of(16,-1,1.5,"15")){var v=variant();v.put("light-level",bad);assertThrows(IllegalArgumentException.class,()->CatalogConfig.parse(config(Map.of("test",v))));}
        var missing=variant();missing.remove("expansion-cooldown-ms");assertThrows(IllegalArgumentException.class,()->CatalogConfig.parse(config(Map.of("test",missing))));
        var unknown=variant();unknown.put("ligth-level",15);assertThrows(IllegalArgumentException.class,()->CatalogConfig.parse(config(Map.of("test",unknown))));
        assertThrows(IllegalArgumentException.class,()->CatalogConfig.parse(config(Map.of("prototype.item-fixture",variant()))));
        assertThrows(IllegalArgumentException.class,()->Selector.parse("uuid:1-1-1-1-1"));assertThrows(IllegalArgumentException.class,()->Selector.parse("name:"));
        var worlds=variant();worlds.put("world-rules",Map.of("defence",Map.of("allow-dimensions",List.of("NORMAL"))));assertThrows(IllegalArgumentException.class,()->CatalogConfig.parse(config(Map.of("test",worlds))));
    }
    @Test void strictYamlPreservesNullAndRejectsDuplicateOrObjectTags(){
        assertTrue(CatalogConfig.parse(CatalogYaml.read("schema: 1\nvariants: {}\n")).variants().isEmpty());
        assertThrows(RuntimeException.class,()->CatalogYaml.read("schema: 1\nschema: 1\nvariants: {}\n"));
        assertThrows(RuntimeException.class,()->CatalogYaml.read("!!java.lang.Runtime {}"));
        assertThrows(IllegalArgumentException.class,()->CatalogConfig.parse(CatalogYaml.read("schema: 1\nvariants: null\n")));
        var nullPolicy=CatalogYaml.read("schema: 1\nvariants: {}\ncapabilities: null\n");assertTrue(nullPolicy.containsKey("capabilities"));assertThrows(IllegalArgumentException.class,()->CatalogConfig.parse(nullPolicy));
        assertThrows(RuntimeException.class,()->CatalogYaml.read("schema: 1\nvariants: &a {}\ncapabilities: *a\n"));
    }
    @Test void fluidsAreIndependentAndLegacyCatalogEncodingsRemainCanonical(){
        var defaults=CatalogConfig.parse(config());
        var dry=defaults.variants().get("test");
        assertEquals(new FluidRule(true,true,false),dry.fluids().water());
        assertEquals(new FluidRule(false,false,false),dry.fluids().lava());
        assertEquals(1,CatalogCodec.definition(dry).version());
        assertEquals(FluidPolicy.DEFAULT,CatalogCodec.candidate(CatalogCodec.candidate(defaults)).variants().get("test").fluids());

        var repaired=variant();
        repaired.put("repair",Map.of("resources",Map.of("minecraft:iron_ingot",2),"cooling-ms",500));
        var legacy=CatalogConfig.parse(config(Map.of("repaired",repaired)));
        assertEquals(2,CatalogCodec.candidate(legacy).version());
        assertEquals(legacy,CatalogCodec.candidate(CatalogCodec.candidate(legacy)));

        var changed=variant();
        changed.put("fluids",Map.of("water",Map.of("place",false,"submerged-defence",true),
                "lava",Map.of("expand",true)));
        var catalog=CatalogConfig.parse(config(Map.of("dry",variant(),"wet",changed,"repaired",repaired)));
        Definition wet=catalog.variants().get("wet");
        assertEquals(new FluidRule(false,true,true),wet.fluids().water());
        assertEquals(new FluidRule(false,true,false),wet.fluids().lava());
        assertTrue(wet.fluids().allowsDefence(HeadFluid.DRY));
        assertTrue(wet.fluids().allowsDefence(HeadFluid.WATER));
        assertFalse(wet.fluids().allowsDefence(HeadFluid.LAVA));
        assertFalse(wet.fluids().allowsDefence(HeadFluid.UNKNOWN));
        assertFalse(FluidPolicy.DEFAULT.allowsDefence(HeadFluid.WATER));
        assertFalse(FluidPolicy.DEFAULT.allowsDefence(HeadFluid.LAVA));
        assertEquals(3,CatalogCodec.definition(wet).version());
        assertEquals(3,CatalogCodec.candidate(catalog).version());
        assertEquals(catalog,CatalogCodec.candidate(CatalogCodec.candidate(catalog)));
        assertEquals(wet,CatalogCodec.definition(CatalogCodec.definition(wet)));
        assertEquals(2,CatalogCodec.definition(catalog.variants().get("repaired")).version());

        changed.put("fluids",Map.of("water",Map.of("place",true,"expand",true,"submerged-defence",false)));
        assertEquals(1,CatalogCodec.candidate(CatalogConfig.parse(config(Map.of("test",changed)))).version());
    }
    @Test void fluidsRejectInvalidValuesAndGroundItemOptions(){
        for(Object invalid:List.of(Map.of("water",Map.of("place","true")),
                Map.of("lava",Map.of("invulnerable",true)),Map.of("unsupported",Map.of()))){
            var changed=variant();changed.put("fluids",invalid);
            assertThrows(IllegalArgumentException.class,()->CatalogConfig.parse(config(Map.of("test",changed))));
        }
    }
    @Test void malformedPayloadCannotBecomeAuthority(){
        var candidate=CatalogConfig.parse(config());var doc=CatalogCodec.candidate(candidate);
        assertThrows(IllegalArgumentException.class,()->CatalogCodec.definition(doc));assertThrows(IllegalArgumentException.class,()->CatalogCodec.candidate(new com.ninja6.infernolantern.repository.RepositoryRecords.Document(1,doc.content()+"AA")));
        assertThrows(IllegalArgumentException.class,()->CatalogCodec.candidate(new com.ninja6.infernolantern.repository.RepositoryRecords.Document(99,doc.content())));
    }
}
