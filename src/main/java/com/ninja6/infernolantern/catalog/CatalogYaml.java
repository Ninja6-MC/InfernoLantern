package com.ninja6.infernolantern.catalog;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import java.util.*;
import java.nio.charset.StandardCharsets;

/** Strict text boundary; explicit null is preserved for validation rather than becoming an absent allowlist. */
public final class CatalogYaml {
    private CatalogYaml(){}
    public static Map<String,?> read(String text){
        Objects.requireNonNull(text);if(text.length()>1_048_576||text.getBytes(StandardCharsets.UTF_8).length>1_048_576)throw new IllegalArgumentException("Catalog input size");
        LoaderOptions options=new LoaderOptions();options.setAllowDuplicateKeys(false);options.setMaxAliasesForCollections(0);options.setNestingDepthLimit(32);options.setCodePointLimit(1_048_576);
        Object loaded=new Yaml(new SafeConstructor(options)).load(text);if(!(loaded instanceof Map<?,?> map))throw new IllegalArgumentException("Catalog object required");
        var result=new LinkedHashMap<String,Object>();for(var entry:map.entrySet()){if(!(entry.getKey() instanceof String key))throw new IllegalArgumentException("String key required");result.put(key,entry.getValue());}return Collections.unmodifiableMap(result);
    }
}
