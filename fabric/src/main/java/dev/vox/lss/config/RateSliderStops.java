package dev.vox.lss.config;

import java.util.ArrayList;

/**
 * The Sodium "Max LOD Download Rate" slider's curve (2026-08-14 granularity request):
 * the slider's int is an INDEX into {@link #STOPS}, not the rate itself, so the low
 * end steps by 10 (a user can pick 20 col/s) while the 3200 no-op top stays reachable
 * in one drag — a linear step-10 {@code Range} over 0..3200 would be 320 ~one-pixel
 * stops. Stop 0 = off; then steps of 10 up to 100, 25 up to 500, 50 up to 1000, and
 * 100 up to 3200 (~59 stops): fine where users actually throttle, coarse where the
 * numbers stop mattering.
 *
 * <p>Kept Sodium-import-free so ConfigValidationTest can classload it under
 * fabric-loader-junit (Sodium is compileOnly — {@code LSSConfigMenu} itself cannot
 * load there): the test pins the round-trip invariant — every nonzero stop must
 * survive the {@code LSSClientConfig.validate()} clamp unchanged, so the lowest
 * nonzero stop IS the clamp floor. A curve edit that breaks that reds Tier 1, not a
 * user report of the UI lying about the saved value.
 */
final class RateSliderStops {

    static final int[] STOPS = buildStops();

    private RateSliderStops() {
    }

    private static int[] buildStops() {
        var stops = new ArrayList<Integer>();
        stops.add(0);
        for (int r = 10; r < 100; r += 10) stops.add(r);
        for (int r = 100; r < 500; r += 25) stops.add(r);
        for (int r = 500; r < 1000; r += 50) stops.add(r);
        for (int r = 1000; r <= 3200; r += 100) stops.add(r);
        return stops.stream().mapToInt(Integer::intValue).toArray();
    }

    /** Nearest curve index for a config value (display snap for hand-edited rates;
     *  values above the top stop — legal, inert — read as the top stop). 0 = off maps
     *  only to stop 0, and stop 0 only to off: a tiny nonzero rate must never display
     *  as "Unlimited", nor off as a throttle. */
    static int nearestIndex(int rate) {
        if (rate <= 0) {
            return 0;
        }
        int best = 1;
        for (int i = 2; i < STOPS.length; i++) {
            if (Math.abs(STOPS[i] - rate) < Math.abs(STOPS[best] - rate)) {
                best = i;
            }
        }
        return best;
    }
}
