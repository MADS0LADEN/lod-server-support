package dev.vox.lss.common.processing;

import io.netty.buffer.Unpooled;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The outbound-buffer gauge, validated against a REAL netty {@link EmbeddedChannel}.
 *
 * <p>Deliberately not a hand-written fake channel: a fake implements
 * {@code bytesBeforeUnwritable}/{@code bytesBeforeWritable} to match whatever formula the
 * author believes, so it asserts the implementation against itself. The plan-review round
 * caught exactly that — and the formula it was asserting was wrong for the netty on this
 * classpath (off by one in both branches, and overflowing on a closed channel).
 */
class OutboundBufferMathTest {

    private static final int LOW = 1024;
    private static final int HIGH = 4096;

    /**
     * A channel whose writes reach the head handler but are never flushed, so they
     * accumulate in the real {@code ChannelOutboundBuffer} — which is the thing under test.
     * (A handler that SWALLOWS the write is wrong here: the message then never reaches the
     * head and the buffer stays empty.)
     */
    private static EmbeddedChannel bufferingChannel() {
        var ch = new EmbeddedChannel();
        ch.config().setWriteBufferWaterMark(new WriteBufferWaterMark(LOW, HIGH));
        return ch;
    }

    private static long probe(EmbeddedChannel ch) {
        var cfg = ch.config();
        return OutboundBufferMath.pendingBytes(ch.isActive(), ch.isWritable(),
                ch.bytesBeforeUnwritable(), ch.bytesBeforeWritable(),
                cfg.getWriteBufferHighWaterMark(), cfg.getWriteBufferLowWaterMark());
    }

    @Test
    void emptyBufferReadsZeroBeyondNettysPerMessageOverhead() {
        var ch = bufferingChannel();
        assertEquals(0, probe(ch), "a channel with nothing queued has no pending bytes");
        ch.finishAndReleaseAll();
    }

    @Test
    void writableBranchTracksQueuedBytes() {
        var ch = bufferingChannel();
        // One 512-byte message, still comfortably under the 4096 high mark.
        ch.write(Unpooled.wrappedBuffer(new byte[512]));
        assertTrue(ch.isWritable(), "premise: still writable below the high mark");
        long pending = probe(ch);
        assertTrue(pending >= 512, "must account for the queued payload, got " + pending);
        assertTrue(pending < HIGH, "still below the high mark, got " + pending);
        ch.finishAndReleaseAll();
    }

    @Test
    void unwritableBranchStillReportsARealDepth() {
        var ch = bufferingChannel();
        ch.write(Unpooled.wrappedBuffer(new byte[HIGH * 4]));
        assertTrue(!ch.isWritable(), "premise: past the high mark the channel is unwritable");
        long pending = probe(ch);
        assertTrue(pending >= HIGH, "unwritable must report at least the high mark, got " + pending);
        assertTrue(pending >= HIGH * 4L, "must reflect the real queued size, got " + pending);
        ch.finishAndReleaseAll();
    }

    @Test
    void closedChannelIsNoSignalNotAnOverflow() {
        // The defect the review caught: Channel.bytesBeforeWritable() returns Long.MAX_VALUE
        // when the outbound buffer is null, while isWritable() returns false — so folding it
        // in as `low + bytesBeforeWritable` overflows to about -9.2e18. Reachable every tick
        // between socket close and the next lifecycle drain.
        var ch = bufferingChannel();
        ch.close().syncUninterruptibly();
        assertEquals(OutboundBufferMath.NO_SIGNAL, probe(ch),
                "a closed channel must report no-signal, never a garbage depth");
    }

    @Test
    void noSignalIsNeverConfusedWithEmpty() {
        assertEquals(OutboundBufferMath.NO_SIGNAL,
                OutboundBufferMath.pendingBytes(false, true, 0, 0, HIGH, LOW),
                "inactive channel");
        assertEquals(OutboundBufferMath.NO_SIGNAL,
                OutboundBufferMath.pendingBytes(true, false, 0, Long.MAX_VALUE, HIGH, LOW),
                "null outbound buffer (MAX_VALUE sentinel)");
        assertEquals(OutboundBufferMath.NO_SIGNAL,
                OutboundBufferMath.pendingBytes(true, false, 0, 0, HIGH, LOW),
                "user-defined unwritable flag over a near-empty buffer must not fabricate a"
                        + " phantom low-water depth");
    }
}
