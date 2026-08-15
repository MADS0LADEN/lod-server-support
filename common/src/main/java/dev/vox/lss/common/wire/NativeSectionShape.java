package dev.vox.lss.common.wire;

/**
 * The NATIVE section-header shape descriptor (V-2/S1, version-port-isolation-plan.md §3):
 * the per-MC-line facts of the native section layout as first-class values, so a support
 * port edits THIS file instead of flavoring the cursor, both NBT serializers, and the
 * three relationship-pinning tests (the recorded ~134-line churn on the 1.21.11 port).
 *
 * <p>Three independent fields (the plan-review MAJOR said two; the V-2 execution
 * review added the cursor's line-level fold — one value cannot express the recorded
 * flavor):
 * <ul>
 *   <li>{@link #NATIVE_COUNT_SHORTS} — LINE-level, consumed by the cursor's parse+emit:
 *       how many count shorts the native section header carries after sectionY.</li>
 *   <li>{@link #foldedCountForNativeHeader} — LINE-level, the cursor emit's one-short
 *       value (client-side vanilla semantics; recorded 1.21.11: the sum).</li>
 *   <li>the family folds ({@link #foldedCountFabricFamily}/{@link #foldedCountPaperFamily})
 *       — PER-PLATFORM-FAMILY, consumed by each serializer's header sites: what VALUE a
 *       one-short header carries. On 1.21.11 Fabric's vanilla recalc writes
 *       {@code nonEmpty + fluid} while Paper's (Moonrise) recalc writes {@code nonEmpty}
 *       alone — same line, different fold.</li>
 * </ul>
 *
 * <p><b>Scope hygiene (pinned by {@code NativeSectionShapeTest}):</b> this descriptor
 * carries NATIVE fields ONLY. The V20 layout is line-invariant wire spec — its two-short
 * count header and {@code V20_BLOCK_MAX_BITS}/{@code V20_BIOME_MAX_BITS} must NEVER
 * derive from here: a per-line edit there forks the never-tiered wire silently, and both
 * ends of that line would agree with each other.
 */
public final class NativeSectionShape {
    private NativeSectionShape() {}

    /**
     * Count shorts in the NATIVE section header after the sectionY byte. 26.x = 2
     * ({@code nonEmptyBlockCount}, {@code fluidCount} — the split pair vanilla's
     * {@code LevelChunkSection.write} emits); 1.21.x = 1. The cursor reads/writes the
     * second short only when this is 2 (V20 always carries both — wire spec).
     */
    public static final int NATIVE_COUNT_SHORTS = 2;

    /**
     * The LINE-level fold the CURSOR's one-short NATIVE emit writes (V-2 review
     * MAJOR-1: this is a third, line-level field — the recorded 1.21.11 cursor sums
     * {@code nonEmpty + fluid} for BOTH families' v20→native egress, because the
     * consumer of cursor-emitted native bytes is always a CLIENT of that line and
     * client semantics are vanilla's count rule; the per-family folds below model the
     * serializers' own disk paths and merely COINCIDE with this on 1.21.11's fabric
     * side). Unreachable while {@link #NATIVE_COUNT_SHORTS} is 2.
     */
    public static int foldedCountForNativeHeader(int nonEmpty, int fluid) {
        throw new IllegalStateException(
                "no single-short fold on a " + NATIVE_COUNT_SHORTS + "-short line");
    }

    /**
     * The value a ONE-short native header carries in the FABRIC family (vanilla's own
     * recalc semantics on that line). Unreachable while {@link #NATIVE_COUNT_SHORTS}
     * is 2 — a 1-short line replaces the body with its recorded fold (1.21.11:
     * {@code nonEmpty + fluid}); reaching it on a two-short line is a caller bug.
     */
    public static int foldedCountFabricFamily(int nonEmpty, int fluid) {
        throw new IllegalStateException(
                "no single-short fold on a " + NATIVE_COUNT_SHORTS + "-short line");
    }

    /**
     * The PAPER-family twin of {@link #foldedCountFabricFamily} (1.21.11: Moonrise's
     * recalc writes {@code nonEmpty} alone). Same unreachability rule.
     */
    public static int foldedCountPaperFamily(int nonEmpty, int fluid) {
        throw new IllegalStateException(
                "no single-short fold on a " + NATIVE_COUNT_SHORTS + "-short line");
    }

    /**
     * True when the two platform families produce identical native count headers — the
     * cross-module corpus parity's strict-vs-normalized flip key: strict byte identity
     * between the fabric and paper goldens holds only while the families agree (26.x:
     * trivially, both write the pair verbatim; 1.21.11: they diverge and the parity
     * compares count-normalized bodies instead).
     */
    public static boolean familiesFoldIdentically() {
        return NATIVE_COUNT_SHORTS == 2;
    }
}
