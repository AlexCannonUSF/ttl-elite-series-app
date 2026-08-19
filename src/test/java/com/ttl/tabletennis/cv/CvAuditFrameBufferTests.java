package com.ttl.tabletennis.cv;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CvAuditFrameBufferTests {

    @Test
    void snapshotReturnsFramesInPushOrder() {
        CvAuditFrameBuffer buffer = new CvAuditFrameBuffer(5);
        buffer.push("m1", Instant.parse("2026-05-17T10:00:00Z"), new byte[]{1});
        buffer.push("m1", Instant.parse("2026-05-17T10:00:01Z"), new byte[]{2});
        buffer.push("m1", Instant.parse("2026-05-17T10:00:02Z"), new byte[]{3});

        List<CvAuditFrameBuffer.AuditFrame> snap = buffer.snapshot("m1");
        assertEquals(3, snap.size());
        assertArrayEquals(new byte[]{1}, snap.get(0).jpegBytes());
        assertArrayEquals(new byte[]{3}, snap.get(2).jpegBytes());
    }

    @Test
    void bufferEvictsOldestWhenOverCapacity() {
        CvAuditFrameBuffer buffer = new CvAuditFrameBuffer(2);
        buffer.push("m1", Instant.now(), new byte[]{1});
        buffer.push("m1", Instant.now(), new byte[]{2});
        buffer.push("m1", Instant.now(), new byte[]{3});

        List<CvAuditFrameBuffer.AuditFrame> snap = buffer.snapshot("m1");
        assertEquals(2, snap.size());
        assertArrayEquals(new byte[]{2}, snap.get(0).jpegBytes());
        assertArrayEquals(new byte[]{3}, snap.get(1).jpegBytes());
    }

    @Test
    void perMatchBuffersAreIndependent() {
        CvAuditFrameBuffer buffer = new CvAuditFrameBuffer(5);
        buffer.push("m1", Instant.now(), new byte[]{1});
        buffer.push("m2", Instant.now(), new byte[]{2});
        buffer.push("m2", Instant.now(), new byte[]{3});

        assertEquals(1, buffer.snapshot("m1").size());
        assertEquals(2, buffer.snapshot("m2").size());
    }

    @Test
    void clearDiscardsThatMatchOnly() {
        CvAuditFrameBuffer buffer = new CvAuditFrameBuffer(5);
        buffer.push("m1", Instant.now(), new byte[]{1});
        buffer.push("m2", Instant.now(), new byte[]{2});

        buffer.clear("m1");

        assertTrue(buffer.snapshot("m1").isEmpty());
        assertEquals(1, buffer.snapshot("m2").size());
    }

    @Test
    void pushIgnoresBlankMatchIdOrEmptyBytes() {
        CvAuditFrameBuffer buffer = new CvAuditFrameBuffer(5);
        buffer.push(null, Instant.now(), new byte[]{1});
        buffer.push("", Instant.now(), new byte[]{1});
        buffer.push("m1", Instant.now(), null);
        buffer.push("m1", Instant.now(), new byte[0]);

        assertEquals(0, buffer.trackedMatches());
    }

    @Test
    void snapshotIsIndependentCopy() {
        CvAuditFrameBuffer buffer = new CvAuditFrameBuffer(5);
        byte[] original = new byte[]{9};
        buffer.push("m1", Instant.now(), original);
        original[0] = 99;

        List<CvAuditFrameBuffer.AuditFrame> snap = buffer.snapshot("m1");
        assertArrayEquals(new byte[]{9}, snap.get(0).jpegBytes());
        assertNotSame(original, snap.get(0).jpegBytes());
    }

    @Test
    void constructorRejectsNonPositiveCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new CvAuditFrameBuffer(0));
        assertThrows(IllegalArgumentException.class, () -> new CvAuditFrameBuffer(-1));
    }

    @Test
    void blankMatchSnapshotIsEmpty() {
        CvAuditFrameBuffer buffer = new CvAuditFrameBuffer(5);
        assertTrue(buffer.snapshot(null).isEmpty());
        assertTrue(buffer.snapshot("").isEmpty());
    }
}
