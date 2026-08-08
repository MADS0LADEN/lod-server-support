package dev.vox.lss.paper;

import dev.vox.lss.common.SharedBandwidthLimiter;
import dev.vox.lss.common.compat.V16CompatManager;
import dev.vox.lss.common.processing.DiskReaderDiagnostics;
import dev.vox.lss.common.processing.ProcessingDiagnostics;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Graceful-degradation coverage for /lsslod via the Supplier seam: the command must answer
 * (never throw at the admin) when the service is not active, when no players are connected,
 * and — the concrete trap — when generation is disabled and getGenerationService() is null
 * while the diag formatter runs.
 */
class PaperCommandsTest {

    private static final String USAGE = "Usage: /lsslod <stats|diag|store>";
    private static final String STORE_USAGE = "Usage: /lsslod store <status|invalidate all>";

    private final List<String> messages = new ArrayList<>();
    private CommandSender sender;

    @BeforeEach
    void setup() {
        messages.clear();
        sender = (CommandSender) Proxy.newProxyInstance(
                CommandSender.class.getClassLoader(), new Class<?>[]{CommandSender.class},
                (p, m, args) -> {
                    if ("sendMessage".equals(m.getName()) && args != null
                            && args.length == 1 && args[0] instanceof String s) {
                        messages.add(s);
                    }
                    return switch (m.getName()) {
                        case "hashCode" -> System.identityHashCode(p);
                        case "equals" -> p == args[0];
                        case "toString" -> "sender";
                        default -> m.getReturnType() == boolean.class ? false : null;
                    };
                });
    }

    private static PaperCommands commands(PaperRequestProcessingService service, PaperConfig config) {
        return new PaperCommands(() -> service, () -> config);
    }

    private boolean run(PaperCommands cmd, String... args) {
        // The Command parameter is unused by the handler; Bukkit registration guarantees label
        return cmd.onCommand(sender, null, "lsslod", args);
    }

    @Test
    void noArgsShowsUsage() {
        assertTrue(run(commands(null, null)));
        assertEquals(List.of(USAGE), messages);
    }

    @Test
    void nullServiceReportsNotActiveInsteadOfThrowing() {
        assertTrue(run(commands(null, null), "stats"));
        assertEquals(List.of("LSS LOD request processing is not active"), messages);
    }

    @Test
    void unknownSubcommandShowsUsage() {
        assertTrue(run(commands(mock(PaperRequestProcessingService.class), null), "bogus"));
        assertEquals(List.of(USAGE), messages);
    }

    @Test
    void unknownSubcommandWithNullServiceReportsNotActive() {
        // Precedence pin: the service-null check answers BEFORE subcommand validation, so
        // /lsslod bogus on an inactive server reports "not active" — never the usage line.
        // A refactor that validates the subcommand first passes both single-rung tests
        // above while flipping this answer.
        assertTrue(run(commands(null, null), "bogus"));
        assertEquals(List.of("LSS LOD request processing is not active"), messages);
    }

    // ---- store verbs (4-agent round R3: Paper parity for the ops surface) ----

    @Test
    void storeStatusWithNoStoreReportsOffUnavailable() {
        var service = mock(PaperRequestProcessingService.class);
        when(service.getLodStore()).thenReturn(null);
        assertTrue(run(commands(service, null), "store", "status"));
        assertEquals(List.of("LOD store: off/unavailable"), messages);
    }

    @Test
    void storeInvalidateAllOnNonPersistentStoreReportsRequiresFull() {
        var service = mock(PaperRequestProcessingService.class);
        when(service.getLodStore())
                .thenReturn(mock(dev.vox.lss.common.store.LodStoreService.class));
        when(service.invalidateStoreAllDimensions()).thenReturn(false);
        assertTrue(run(commands(service, null), "store", "invalidate", "all"));
        assertEquals(List.of("Invalidate-all requires the persistent store — this session degraded to the in-memory tier at boot (SQLite could not open; see the startup warning)"),
                messages);
    }

    @Test
    void storeInvalidateAllOnPersistentStoreAcknowledges() {
        var service = mock(PaperRequestProcessingService.class);
        when(service.getLodStore())
                .thenReturn(mock(dev.vox.lss.common.store.LodStoreService.class));
        when(service.invalidateStoreAllDimensions()).thenReturn(true);
        assertTrue(run(commands(service, null), "store", "invalidate", "all"));
        assertEquals(List.of("LOD store: dropping all rows (background) — re-warms from serves"),
                messages);
    }

    @Test
    void bareStoreVerbShowsStoreUsage() {
        var service = mock(PaperRequestProcessingService.class);
        assertTrue(run(commands(service, null), "store"));
        assertEquals(List.of(STORE_USAGE), messages);
    }

    @Test
    void statsWithZeroPlayersReportsNoPlayers() {
        var service = mock(PaperRequestProcessingService.class);
        when(service.getPlayers()).thenReturn(Map.of());
        assertTrue(run(commands(service, null), "stats"));
        assertEquals(List.of("No players connected with LSS"), messages);
    }

    @Test
    void diagWithGenerationDisabledShowsDisabledLine() {
        var service = mock(PaperRequestProcessingService.class);
        var offThread = mock(PaperOffThreadProcessor.class);
        when(offThread.getDiagnostics()).thenReturn(new ProcessingDiagnostics());
        doReturn(offThread).when(service).getOffThreadProcessor();
        var diskReader = mock(PaperChunkDiskReader.class);
        when(diskReader.getDiag()).thenReturn(new DiskReaderDiagnostics());
        when(diskReader.getDiagnostics()).thenReturn("idle");
        when(service.getDiskReader()).thenReturn(diskReader);
        when(service.getBandwidthLimiter()).thenReturn(new SharedBandwidthLimiter(1024));
        when(service.getV16CompatManager()).thenReturn(new V16CompatManager());
        when(service.getDialectTracker()).thenReturn(new dev.vox.lss.common.compat.WireDialectTracker());
        when(service.getTickDiagnostics()).thenReturn("tick");
        when(service.getTickDiag()).thenReturn(new dev.vox.lss.common.processing.TickDiagnostics());
        when(service.getPlayers()).thenReturn(Map.of());
        // getGenerationService() returns null (generation disabled) — the path that must not NPE

        assertTrue(run(commands(service, new PaperConfig()), "diag"));

        assertEquals("=== LSS LOD Diagnostics ===", messages.get(0));
        assertTrue(messages.contains("Generation: disabled"),
                "null generation service renders as 'disabled': " + messages);
        assertFalse(messages.contains("Generation: null"),
                "disabled generation must not format the null diagnostics string");
        assertTrue(messages.stream().noneMatch(m -> m.startsWith("V18Compat")),
                "an untouched v18 rung must render no line: " + messages);
    }

    @Test
    void diagRendersTheV18CompatLineThroughTheCommandCallSite() {
        // The tracker -> withV18Line -> output plumbing at the COMMAND call site
        // (v18-compat design §2.7): the formatter-level slot test cannot catch a deleted
        // .withV18Line(...) chain link in PaperCommands, this can.
        var service = mock(PaperRequestProcessingService.class);
        var offThread = mock(PaperOffThreadProcessor.class);
        when(offThread.getDiagnostics()).thenReturn(new ProcessingDiagnostics());
        doReturn(offThread).when(service).getOffThreadProcessor();
        var diskReader = mock(PaperChunkDiskReader.class);
        when(diskReader.getDiag()).thenReturn(new DiskReaderDiagnostics());
        when(diskReader.getDiagnostics()).thenReturn("idle");
        when(service.getDiskReader()).thenReturn(diskReader);
        when(service.getBandwidthLimiter()).thenReturn(new SharedBandwidthLimiter(1024));
        when(service.getV16CompatManager()).thenReturn(new V16CompatManager());
        var tracker = new dev.vox.lss.common.compat.WireDialectTracker();
        tracker.onHandshake(java.util.UUID.randomUUID(),
                dev.vox.lss.common.HandshakeGate.WireDialect.V18);
        when(service.getDialectTracker()).thenReturn(tracker);
        when(service.getTickDiagnostics()).thenReturn("tick");
        when(service.getTickDiag()).thenReturn(new dev.vox.lss.common.processing.TickDiagnostics());
        when(service.getPlayers()).thenReturn(Map.of());

        assertTrue(run(commands(service, new PaperConfig()), "diag"));
        assertTrue(messages.contains("Dialects: v20=0, v19=0, v18=1, v16=0, started=0/0/1/0"),
                "a live v18 session must render the Dialects line through the command: " + messages);
    }

    @Test
    void diagWithEnabledFalseConfigRendersAndStaysServiceable() {
        // enabled=false disables serving, not observability: diag must still answer with
        // the full line ladder and the Config line must carry the false flag.
        var service = mock(PaperRequestProcessingService.class);
        var offThread = mock(PaperOffThreadProcessor.class);
        when(offThread.getDiagnostics()).thenReturn(new ProcessingDiagnostics());
        doReturn(offThread).when(service).getOffThreadProcessor();
        var diskReader = mock(PaperChunkDiskReader.class);
        when(diskReader.getDiag()).thenReturn(new DiskReaderDiagnostics());
        when(diskReader.getDiagnostics()).thenReturn("idle");
        when(service.getDiskReader()).thenReturn(diskReader);
        when(service.getBandwidthLimiter()).thenReturn(new SharedBandwidthLimiter(1024));
        when(service.getV16CompatManager()).thenReturn(new V16CompatManager());
        when(service.getDialectTracker()).thenReturn(new dev.vox.lss.common.compat.WireDialectTracker());
        when(service.getTickDiagnostics()).thenReturn("tick");
        when(service.getTickDiag()).thenReturn(new dev.vox.lss.common.processing.TickDiagnostics());
        when(service.getPlayers()).thenReturn(Map.of());
        var config = new PaperConfig();
        config.enabled = false;

        assertTrue(run(commands(service, config), "diag"));

        assertEquals("=== LSS LOD Diagnostics ===", messages.get(0));
        assertTrue(messages.get(1).startsWith("Config: enabled=false, lodDist=512, bw/player="),
                "the Config line must render the disabled flag and the config values: " + messages.get(1));
        assertTrue(messages.stream().anyMatch(m -> m.equals("Xray: active=off, masked_sections=0")),
                "no active mask manager renders the off xray line: " + messages);
        assertTrue(messages.stream().anyMatch(m ->
                        m.equals("Dialects: v20=0, v19=0, v18=0, v16=0, started=0/0/0/0")),
                "the Dialects line renders unconditionally (the v20 count IS the live"
                        + " LOD-session count): " + messages);
        assertEquals(10, messages.size(),
                "all ten diagnostic lines render with no players connected: " + messages);
    }

    @Test
    void diagIsCaseInsensitive() {
        var service = mock(PaperRequestProcessingService.class);
        when(service.getPlayers()).thenReturn(Map.of());
        assertTrue(run(commands(service, null), "STATS"));
        assertEquals(List.of("No players connected with LSS"), messages);
    }

    @Test
    void tabCompleteFiltersByPrefix() {
        var cmd = commands(null, null);
        assertEquals(List.of("stats", "diag", "store"), cmd.onTabComplete(sender, null, "lsslod", new String[]{""}));
        assertEquals(List.of("stats", "store"), cmd.onTabComplete(sender, null, "lsslod", new String[]{"st"}));
        assertEquals(List.of("diag"), cmd.onTabComplete(sender, null, "lsslod", new String[]{"D"}));
        assertEquals(List.of(), cmd.onTabComplete(sender, null, "lsslod", new String[]{"zz"}));
        assertEquals(List.of(), cmd.onTabComplete(sender, null, "lsslod", new String[]{"stats", "x"}));
        assertEquals(List.of("status", "invalidate"),
                cmd.onTabComplete(sender, null, "lsslod", new String[]{"store", ""}));
        assertEquals(List.of("all"),
                cmd.onTabComplete(sender, null, "lsslod", new String[]{"store", "invalidate", ""}));
    }
}
