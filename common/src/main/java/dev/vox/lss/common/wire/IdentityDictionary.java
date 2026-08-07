package dev.vox.lss.common.wire;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * The v20 column dictionary: one entry per distinct canonical identity, indexed in
 * FIRST-SEEN order (deterministic given section walk order — pinned by goldens).
 * Blocks and biomes share one table; context (which palette references an index)
 * disambiguates. A clear column never touches the dictionary, so it emits empty —
 * the {@code dictCount==0 ⇔ sectionCount==0} ghost-clear invariant costs nothing here
 * and is enforced at the emitters.
 *
 * <p>Every identity is {@link IdentityCodec#validate validated} on first insertion:
 * the dictionary is the last common choke point before strings hit the wire (or a
 * migrated store row), so an out-of-charset identity fails loudly here rather than
 * shipping unparseable.
 */
public final class IdentityDictionary {
    private final LinkedHashMap<String, Integer> indices = new LinkedHashMap<>();

    /** Returns the identity's dictionary index, assigning the next one on first sight. */
    public int indexOf(String identity) {
        Integer existing = indices.get(identity);
        if (existing != null) {
            return existing;
        }
        IdentityCodec.validate(identity);
        int index = indices.size();
        indices.put(identity, index);
        return index;
    }

    public int size() {
        return indices.size();
    }

    /** Entries in index order (index {@code i} is {@code entries().get(i)}). */
    public List<String> entries() {
        return new ArrayList<>(indices.keySet());
    }
}
