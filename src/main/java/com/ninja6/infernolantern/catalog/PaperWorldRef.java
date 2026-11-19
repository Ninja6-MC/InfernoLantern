package com.ninja6.infernolantern.catalog;

import org.bukkit.*;
import static com.ninja6.infernolantern.catalog.CatalogConfig.*;

/** Runtime identity is a UUID; names are policy selectors and never replace stored world identity. */
public final class PaperWorldRef {
    private PaperWorldRef(){}
    public static WorldRef capture(World world){
        if(!Bukkit.isPrimaryThread())throw new IllegalStateException("World owner required");
        Dimension dimension=switch(world.getEnvironment()){case NORMAL->Dimension.OVERWORLD;case NETHER->Dimension.NETHER;case THE_END->Dimension.END;case CUSTOM->Dimension.CUSTOM;};
        // CUSTOM is represented by the API, but support is not claimed without a native validation gate.
        return new WorldRef(world.getUID(),world.getName(),dimension,false);
    }
}
