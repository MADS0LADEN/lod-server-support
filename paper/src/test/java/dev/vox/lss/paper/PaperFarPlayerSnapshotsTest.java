package dev.vox.lss.paper;

import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.metadata.MetadataValue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The far-player privacy ladder (Folia review 2026-08-27 R2/R7): the pump reads a
 * cross-region permissible + vanish metadata on Folia — the ladder is CONTAINED per
 * player and fails HIDDEN (a raced/throwing read must never leak a hidden or vanished
 * player's position, and one broken permissible must not abort the snapshot pass for
 * everyone — the pre-fix shape). First paper-module coverage of this class.
 */
class PaperFarPlayerSnapshotsTest {

    @BeforeEach
    void resetLatch() {
        PaperFarPlayerSnapshots.resetHiddenReadWarnedForTest();
    }

    private static org.bukkit.entity.Player bukkit() {
        var p = mock(org.bukkit.entity.Player.class);
        when(p.getMetadata(anyString())).thenReturn(List.of());
        return p;
    }

    @Test
    void aCleanUnprivilegedPlayerIsVisible() {
        assertFalse(PaperFarPlayerSnapshots.hiddenFor(bukkit()),
                "no node, no vanish: visible (the mode default)");
    }

    @Test
    void eitherBrandSpellingHides() {
        for (String node : new String[]{"lss.farplayers.hidden", "vss.farplayers.hidden"}) {
            var p = bukkit();
            when(p.hasPermission(node)).thenReturn(true);
            assertTrue(PaperFarPlayerSnapshots.hiddenFor(p),
                    node + " must hide (grant model: EITHER spelling takes effect)");
        }
    }

    @Test
    void vanishedMetadataHides() {
        var p = bukkit();
        var plugin = mock(org.bukkit.plugin.Plugin.class);
        when(p.getMetadata("vanished"))
                .thenReturn(List.<MetadataValue>of(new FixedMetadataValue(plugin, true)));
        assertTrue(PaperFarPlayerSnapshots.hiddenFor(p),
                "the vanish bridge must hide a vanished target");
    }

    @Test
    void aThrowingPermissibleFailsHIDDENNotOpen() {
        // The R7 direction decision, pinned: on Folia a cross-region PermissibleBase
        // read can throw where Paper's main thread never does. Hiding too much for one
        // interval is recoverable; leaking a hidden player's position is not.
        var p = bukkit();
        when(p.hasPermission(anyString())).thenThrow(new IllegalStateException("raced"));
        assertTrue(PaperFarPlayerSnapshots.hiddenFor(p),
                "a throwing privacy read must HIDE, never leak");
    }

    @Test
    void aThrowingVanishReadFailsHIDDENNotOpen() {
        // Reverses the E2 fail-open (recorded in the plan's R7): a LazyMetadataValue
        // callable that trips Folia's region-ownership checks used to answer
        // "not vanished" forever — a vanished staff member broadcast for the run.
        var p = bukkit();
        when(p.getMetadata("vanished")).thenThrow(new IllegalStateException("region-owned"));
        assertTrue(PaperFarPlayerSnapshots.hiddenFor(p),
                "a throwing vanish read must HIDE the target, never leak the position");
    }

    @Test
    void theThrowIsContainedPerPlayerNotPerPass() {
        // The pre-fix failure shape: one throwing permissible aborted the snapshot
        // loop for ALL players. hiddenFor must contain, so a healthy player's
        // evaluation right after a throwing one still works.
        var broken = bukkit();
        when(broken.hasPermission(anyString())).thenThrow(new IllegalStateException("x"));
        assertTrue(PaperFarPlayerSnapshots.hiddenFor(broken));
        assertFalse(PaperFarPlayerSnapshots.hiddenFor(bukkit()),
                "the next player's read must be unaffected by the previous throw");
    }
}
