package dev.vox.lss.common.wire;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The protocol-20 canonical identity form (cross-version-identity-encoding-plan §2.1):
 * {@code namespace:path[k1=v1,k2=v2]} — ALL properties, sorted by property name, no
 * spaces; bare {@code namespace:path} when the state has none. Biomes are bare
 * identifiers. This is a NEW strict wire form — deliberately NOT
 * {@code BlockState.toString()} (which {@code RegistryFingerprint} uses and persists
 * in store meta; that stringifier stays untouched).
 *
 * <p><b>Charset safety is validated, not assumed</b> (the plan's pin): Minecraft's
 * identifier charset ({@code [a-z0-9_.-]} namespace, {@code [a-z0-9_./-]} path) and
 * serialized property names/values cannot contain the four structural characters
 * {@code [ ] , =} or spaces, so the form needs no escaping. {@link #validate} enforces
 * the full grammar; the platform-side table generators run every registry entry
 * through it, so an out-of-charset modded value fails loudly at table build, never as
 * silent wire corruption.
 *
 * <p>{@link #parse} accepts ONLY the canonical form (strictly ascending property
 * names, no duplicates) — a decoder that tolerated non-canonical spellings would
 * make two wire dictionaries encode one state, breaking dictionary determinism.
 */
public final class IdentityCodec {
    private IdentityCodec() {}

    /** A parsed identity: {@code name} is {@code namespace:path}; properties sorted. */
    public record ParsedIdentity(String name, List<Map.Entry<String, String>> properties) {}

    /**
     * Builds the canonical form from a registry name ({@code namespace:path}) and a
     * property map (any iteration order — sorted here). Validates the result.
     */
    public static String canonical(String name, Map<String, String> properties) {
        String identity;
        if (properties == null || properties.isEmpty()) {
            identity = name;
        } else {
            var sorted = new TreeMap<>(properties);
            var sb = new StringBuilder(name).append('[');
            boolean first = true;
            for (var e : sorted.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) {
                    // StringBuilder would render a null as the literal "null", which
                    // then VALIDATES — a silent-corruption path in a fail-loud class.
                    throw new IllegalArgumentException("null property key/value for " + name);
                }
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append(e.getKey()).append('=').append(e.getValue());
            }
            identity = sb.append(']').toString();
        }
        validate(identity);
        return identity;
    }

    /** Throws {@link IllegalArgumentException} unless {@code identity} is canonical. */
    public static void validate(String identity) {
        parse(identity);
    }

    /**
     * Strict parse of the canonical form. Rejects: missing/multiple {@code :},
     * empty segments, out-of-charset characters, an empty {@code []}, unsorted or
     * duplicate property names, and any structural malformation.
     */
    public static ParsedIdentity parse(String identity) {
        if (identity == null || identity.isEmpty()) {
            throw new IllegalArgumentException("empty identity");
        }
        int bracket = identity.indexOf('[');
        String name = bracket < 0 ? identity : identity.substring(0, bracket);

        int colon = name.indexOf(':');
        if (colon <= 0 || colon != name.lastIndexOf(':') || colon == name.length() - 1) {
            throw new IllegalArgumentException("identity name needs exactly one interior ':': " + identity);
        }
        checkCharset(name, 0, colon, NAMESPACE_CHARS, "namespace", identity);
        checkCharset(name, colon + 1, name.length(), PATH_CHARS, "path", identity);

        if (bracket < 0) {
            return new ParsedIdentity(name, List.of());
        }
        if (identity.charAt(identity.length() - 1) != ']') {
            throw new IllegalArgumentException("unterminated property list: " + identity);
        }
        String body = identity.substring(bracket + 1, identity.length() - 1);
        if (body.isEmpty()) {
            throw new IllegalArgumentException("empty property list must be the bare form: " + identity);
        }
        var props = new ArrayList<Map.Entry<String, String>>();
        String previousKey = null;
        for (String pair : body.split(",", -1)) {
            int eq = pair.indexOf('=');
            if (eq <= 0 || eq != pair.lastIndexOf('=') || eq == pair.length() - 1) {
                throw new IllegalArgumentException("malformed property '" + pair + "' in: " + identity);
            }
            String key = pair.substring(0, eq);
            String value = pair.substring(eq + 1);
            checkCharset(key, 0, key.length(), PROP_NAME_CHARS, "property name", identity);
            checkCharset(value, 0, value.length(), PROP_VALUE_CHARS, "property value", identity);
            if (previousKey != null && previousKey.compareTo(key) >= 0) {
                throw new IllegalArgumentException("property names not strictly ascending at '"
                        + key + "' in: " + identity);
            }
            previousKey = key;
            props.add(Map.entry(key, value));
        }
        return new ParsedIdentity(name, List.copyOf(props));
    }

    // Charsets: namespace/path follow Minecraft's Identifier rules; property names are
    // Minecraft's lowercase serialized names, values their getName(T) output (booleans,
    // non-negative ints, lowercase enum names — '+' '.' '-' allowed defensively for
    // modded numeric-ish values). None admit '[' ']' ',' '=' or whitespace — the
    // no-escaping premise, enforced here and pinned by IdentityCodecTest.
    private static final boolean[] NAMESPACE_CHARS = charset("abcdefghijklmnopqrstuvwxyz0123456789_.-");
    private static final boolean[] PATH_CHARS = charset("abcdefghijklmnopqrstuvwxyz0123456789_./-");
    private static final boolean[] PROP_NAME_CHARS = charset("abcdefghijklmnopqrstuvwxyz0123456789_");
    private static final boolean[] PROP_VALUE_CHARS = charset("abcdefghijklmnopqrstuvwxyz0123456789_.+-");

    private static boolean[] charset(String allowed) {
        var table = new boolean[128];
        for (int i = 0; i < allowed.length(); i++) {
            table[allowed.charAt(i)] = true;
        }
        return table;
    }

    private static void checkCharset(String s, int from, int to, boolean[] allowed,
                                     String what, String identity) {
        if (from >= to) {
            throw new IllegalArgumentException("empty " + what + " in: " + identity);
        }
        for (int i = from; i < to; i++) {
            char c = s.charAt(i);
            if (c >= 128 || !allowed[c]) {
                throw new IllegalArgumentException("illegal character '" + c + "' in "
                        + what + " of: " + identity);
            }
        }
    }
}
