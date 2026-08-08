package dev.vox.lss.common.compat;

import com.viaversion.viaversion.api.Via;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Via probe's resolve ladder (XVER §7), driven against the real-package-name stubs
 * under {@code com/viaversion/} — the {@code MoonriseReadCompatTest} pattern:
 * production {@code Class.forName} finds the stub, and every failure shape must read as
 * NO_SIGNAL because the guard DENIES REGISTRATION off this answer (fail-open is the
 * load-bearing property, not a nicety). Instance-scoped {@code build} keeps the tests
 * order-independent of the production holder.
 */
class ViaProbeTest {

    private static final UUID PLAYER = UUID.randomUUID();
    private final AtomicInteger warns = new AtomicInteger();

    private ViaProbe probe() {
        return ViaProbe.build(Class::forName, (detail, cause) -> this.warns.incrementAndGet());
    }

    @BeforeEach
    void resetStub() {
        Via.API = null;
        Via.throwOnGetApi = null;
    }

    @Test
    void resolvesAndReadsThePlayerProtocol() {
        Via.API = uuid -> {
            assertEquals(PLAYER, uuid);
            return 763;
        };
        assertEquals(763, probe().playerProtocolOrNoSignal(PLAYER));
        assertEquals(0, this.warns.get(), "clean resolution must not warn");
    }

    @Test
    void throwingGetApiIsNoSignalForThatCallOnlyNeverALatch() {
        // Via's own "not yet initialized" precondition throw (IllegalArgumentException
        // upstream): a handshake can race plugin init, and a later call may succeed —
        // no permanent latch, no log.
        var p = probe();
        Via.throwOnGetApi = new IllegalArgumentException("Via not initialized");
        assertEquals(ViaProbe.NO_SIGNAL, p.playerProtocolOrNoSignal(PLAYER));
        Via.throwOnGetApi = null;
        Via.API = uuid -> 340;
        assertEquals(340, p.playerProtocolOrNoSignal(PLAYER),
                "the SAME probe instance must recover once Via is up");
        assertEquals(0, this.warns.get());
    }

    @Test
    void nullApiIsNoSignal() {
        assertEquals(ViaProbe.NO_SIGNAL, probe().playerProtocolOrNoSignal(PLAYER));
        assertEquals(0, this.warns.get());
    }

    @Test
    void absentClassIsSilentNoSignal() {
        // Via not installed — the common case. Must be SILENT: warn-once is reserved
        // for present-but-unresolvable (API drift worth reporting).
        var p = ViaProbe.build(name -> {
            throw new ClassNotFoundException(name);
        }, (detail, cause) -> this.warns.incrementAndGet());
        assertEquals(ViaProbe.NO_SIGNAL, p.playerProtocolOrNoSignal(PLAYER));
        assertEquals(0, this.warns.get());
    }

    @Test
    void unresolvableShapeWarnsOnceAtBuildAndStaysNoSignal() {
        // A class by the right name with the wrong shape = Via API drift: the one
        // failure worth a log line, because the mismatch guard silently stops working.
        var p = ViaProbe.build(name -> String.class, (detail, cause) -> this.warns.incrementAndGet());
        assertEquals(ViaProbe.NO_SIGNAL, p.playerProtocolOrNoSignal(PLAYER));
        assertEquals(ViaProbe.NO_SIGNAL, p.playerProtocolOrNoSignal(PLAYER));
        assertEquals(1, this.warns.get(), "warn at build, once — never per call");
    }

    @Test
    void theProductionHolderResolvesTheRealClassName() {
        // MAJOR-4: the static entry (Holder + the VIA_CLASS constant + the warn
        // lambda) is what production actually runs — a typo'd FQN there is a
        // permanently dead guard that every instance-scoped test above misses. The
        // stub on this classpath IS what Class.forName finds. Order-independent:
        // resolution never needs a non-null API, only the class shape.
        Via.API = uuid -> 763;
        assertEquals(763, ViaProbe.playerProtocol(PLAYER));
        Via.API = null;
        assertEquals(ViaProbe.NO_SIGNAL, ViaProbe.playerProtocol(PLAYER));
    }

    @Test
    void theMismatchRuleIsFailOpen() {
        int nativeProtocol = 774;
        assertFalse(ViaProbe.isMismatch(ViaProbe.NO_SIGNAL, nativeProtocol),
                "no-signal must never deny");
        assertFalse(ViaProbe.isMismatch(0, nativeProtocol), "0 is not a positive answer");
        assertFalse(ViaProbe.isMismatch(nativeProtocol, nativeProtocol),
                "the native protocol is not a mismatch");
        assertTrue(ViaProbe.isMismatch(763, nativeProtocol),
                "a positive, different protocol IS the mismatch");
    }
}
