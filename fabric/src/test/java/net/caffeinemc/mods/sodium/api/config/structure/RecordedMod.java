package net.caffeinemc.mods.sodium.api.config.structure;

import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

public class RecordedMod implements ModOptionsBuilder {
    public final String id;
    public final String name;
    public final String version;
    public Identifier icon;
    public final List<RecordedPage> pages = new ArrayList<>();

    RecordedMod(String id, String name, String version) {
        this.id = id;
        this.name = name;
        this.version = version;
    }

    @Override
    public ModOptionsBuilder setIcon(Identifier icon) {
        this.icon = icon;
        return this;
    }

    @Override
    public ModOptionsBuilder addPage(PageBuilder page) {
        pages.add((RecordedPage) page);
        return this;
    }
}
