package dev.vox.lss.compat;

import dev.vox.lss.common.Brand;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The Voxy storage-override cross-check: the criterion that decides whether the live
 * storage root may be deleted, and everything the user is told about that decision.
 * Both halves live here on purpose — the {@code /lss reset voxy-force} prompt promises
 * "this is the directory that will be deleted", and it can only keep that promise while
 * it and the wipe share one predicate. The text is likewise ONE assembler behind BOTH
 * the client log and the in-game feedback, so the two can never drift apart.
 *
 * <p>Before #4 a skipped wipe produced a single warn that named neither root: the user
 * was told the wipe was "skipped (fail-safe)" and had nothing to act on. The wipe-skip
 * itself is CORRECT and deliberate — a Flashback/ReplayMod storage override points at
 * the ORIGIN server's real store, which passes directory containment, so the derived-root
 * cross-check in {@link #shouldWipeLiveRoot} is the only thing standing between
 * {@code /lss reset} and someone else's LODs. What #4 changes is only what the user is
 * TOLD: both roots, the likely cause, and the explicit, path-first override that lets
 * them accept the risk on purpose.
 *
 * <p>Pure by construction: no MC, no IO, no state beyond {@link Brand}. Paths are
 * rendered verbatim ({@code toString}) — they arrive absolute from both Voxy's
 * {@code getStorageBasePath()} and LSS's own derivation, and re-absolutising a path here
 * against the process CWD would print a directory that does not exist.
 */
public final class VoxyStorageOverride {

    /** Rendered in place of a root that could not be read or derived. Never "null" —
     *  the user has to be able to tell "we did not look" from a real path. */
    public static final String UNRESOLVED = "<unresolved>";

    private VoxyStorageOverride() {
    }

    /**
     * What the cross-check found. Three ways to fail, and they are NOT the same thing to
     * a user: a root nobody could read, a root nobody could check, and a root that was
     * checked and disagreed. Only the last one licenses "another mod redirected Voxy's
     * storage" — saying that about an underivable expected root is a guess dressed as a
     * diagnosis (issue #4 follow-up).
     */
    public enum Verdict {
        /** live == derived: an ordinary session. Forcing changes nothing. */
        MATCHES,
        /** live != derived: a storage override is active (Flashback/ReplayMod/other). */
        OVERRIDDEN,
        /** the derived root is unavailable, so the live root was never checked at all. */
        UNVERIFIABLE,
        /** no live root was read — there is nothing to check and nothing to wipe. */
        NO_LIVE_ROOT
    }

    /** The cross-check, as a value. Single source of truth for both the wipe decision
     *  and everything the user is told about it. */
    public static Verdict verdict(Path liveRoot, Path expectedRoot) {
        if (liveRoot == null) return Verdict.NO_LIVE_ROOT;
        if (expectedRoot == null) return Verdict.UNVERIFIABLE;
        return samePath(liveRoot, expectedRoot) ? Verdict.MATCHES : Verdict.OVERRIDDEN;
    }

    /**
     * May the LIVE storage root be deleted?
     *
     * <p>The default answer is the stage-D review MAJOR and issue #4 does not relax it:
     * only a root that was read AND verified equal to this connection's own derivation
     * is wipeable. Everything else — unreadable, unverifiable, or disagreeing — is not.
     *
     * <p>{@code force} waives the verdict and NOTHING else. It is reachable only through
     * {@code /lss reset voxy-force confirm} — i.e. after the user has been shown the
     * exact path by {@link #forcePromptLines} — and the wipe it authorises is still
     * gated by {@link VoxyCompat#wipeVoxyStore}'s containment fence, the second fence.
     *
     * <p>Lives here rather than on {@link VoxyCompat} so that asking "is an override
     * active?" costs nothing: {@code VoxyCompat} is the reflective MC/Voxy bridge and
     * merely initialising it drags the Minecraft classes in.
     */
    public static boolean shouldWipeLiveRoot(Path liveRoot, Path expectedRoot, boolean force) {
        if (liveRoot == null) return false;
        if (force) return true;
        return verdict(liveRoot, expectedRoot) == Verdict.MATCHES;
    }

    /** Absolute-normalised path equality — the cross-check's comparison, isolated so
     *  every caller of it compares roots the same way. */
    /** Absolute-normalised path equality — the cross-check's comparison, isolated so
     *  every caller of it compares roots the same way. */
    public static boolean samePath(Path a, Path b) {
        if (a == null || b == null) return false;
        return a.toAbsolutePath().normalize().equals(b.toAbsolutePath().normalize());
    }

    /** Display form of a possibly-absent root. */
    public static String render(Path root) {
        return root == null ? UNRESOLVED : root.toString();
    }

    /** The two root lines — the actionable core of every report here, written ONCE so
     *  the skipped-wipe report and the force prompt cannot drift apart (they are also
     *  asserted verbatim in two separate test classes). */
    static List<String> rootLines(Path liveRoot, Path expectedRoot) {
        return List.of("  Voxy's live storage root: " + render(liveRoot),
                "  " + Brand.shortName() + "'s expected root: " + render(expectedRoot));
    }

    /** Why the roots look the way they do — one sentence per {@link Verdict}, shared by
     *  both reports so a reworded cause changes in exactly one place. */
    static String causeLine(Verdict verdict) {
        return "  " + switch (verdict) {
            case OVERRIDDEN -> "This usually means another mod has redirected Voxy's storage "
                    + "location (a replay mod, or any other storage override).";
            case UNVERIFIABLE -> Brand.shortName() + " could not derive the expected root for "
                    + "this connection, so the live root was never checked against anything.";
            case MATCHES -> "The live root matches the root " + Brand.shortName()
                    + " derived for this connection.";
            case NO_LIVE_ROOT -> "Voxy did not report a storage root, so there was nothing to "
                    + "check or to delete.";
        };
    }

    /**
     * The whole "your LODs are still on disk, here is where" report: headline + the two
     * roots + the cause + (where it would actually help) the override that lifts the
     * cross-check.
     *
     * <p>{@code offerForce} is false when the caller IS the forced run — telling someone
     * who just ran {@code voxy-force} to run {@code voxy-force} is noise. It is also
     * suppressed when there is no live root: {@code voxy-force} can only answer that
     * case with "nothing to force-wipe", so offering it would send the user down a dead
     * end (issue #4 follow-up).
     *
     * @param liveRoot     what the running Voxy instance reports, or null if unreadable
     * @param expectedRoot what LSS derived for this connection, or null if underivable
     */
    public static List<String> wipeSkippedLines(Path liveRoot, Path expectedRoot,
                                                boolean offerForce) {
        Verdict verdict = verdict(liveRoot, expectedRoot);
        var out = new ArrayList<String>();
        out.add("Voxy's LOD store was NOT deleted: " + switch (verdict) {
            case NO_LIVE_ROOT -> "its live storage root could not be read (fail-safe).";
            case UNVERIFIABLE -> "the live storage root could not be verified against the root "
                    + Brand.shortName() + " derives for this connection (fail-safe).";
            default -> "the live storage root does not match the root " + Brand.shortName()
                    + " derived for this connection (fail-safe).";
        });
        out.addAll(rootLines(liveRoot, expectedRoot));
        out.add(causeLine(verdict));
        if (offerForce && liveRoot != null) {
            out.add("  To delete the live root anyway, run '/" + Brand.clientCommand()
                    + " reset voxy-force' — it shows the path before deleting anything.");
        }
        return List.copyOf(out);
    }

    /**
     * Stage 1 of {@code /lss reset voxy-force}: the user sees the exact directory that
     * stage 2 would delete, why the safety check fired, and the confirm form — and is
     * told, in as many words, that nothing has been deleted yet.
     *
     * <p>The branches that end early (no Voxy, no readable root) deliberately do NOT
     * name the confirm form: there is nothing to confirm, and offering a stage 2 that
     * cannot act would be a lie.
     */
    public static List<String> forcePromptLines(ModCompat.VoxyStorageProbe probe,
                                                boolean managerActive) {
        if (!probe.voxyPresent()) {
            return List.of("Voxy is not installed — there is no Voxy store to force-wipe.");
        }
        if (probe.liveRoot() == null) {
            return List.of("Voxy's live storage root could not be read — there is nothing to "
                    + "force-wipe. Run '/" + Brand.clientCommand() + " reset' instead; it "
                    + "clears the root " + Brand.shortName() + " derives for this connection ("
                    + render(probe.expectedRoot()) + ").");
        }
        Verdict verdict = probe.verdict();
        var out = new ArrayList<String>();
        out.add("FORCED Voxy wipe — this DELETES the directory below, overriding the "
                + "storage-override safety check:");
        out.addAll(rootLines(probe.liveRoot(), probe.expectedRoot()));
        out.add(causeLine(verdict));
        switch (verdict) {
            case OVERRIDDEN -> out.add("  If that store belongs to ANOTHER server or to a "
                    + "replay, DO NOT confirm.");
            case UNVERIFIABLE -> out.add("  Confirm only if you recognise that path as this "
                    + "connection's own Voxy store.");
            case MATCHES -> out.add("  No override detected — this will behave exactly like "
                    + "'/" + Brand.clientCommand() + " reset'.");
            case NO_LIVE_ROOT -> { /* unreachable: the null live root returned above */ }
        }
        if (!probe.containedForWipe()) {
            out.add("  That path is OUTSIDE Voxy's storage roots, so the wipe will still be "
                    + "refused by the containment fail-safe — nothing can be deleted.");
        }
        if (!managerActive) {
            out.add("  There is no active " + Brand.shortName() + " session, so there is NO "
                    + "re-stream — terrain repopulates only from vanilla chunk loading.");
        }
        out.add("Nothing has been deleted. Run '/" + Brand.clientCommand()
                + " reset voxy-force confirm' to proceed.");
        return List.copyOf(out);
    }
}
