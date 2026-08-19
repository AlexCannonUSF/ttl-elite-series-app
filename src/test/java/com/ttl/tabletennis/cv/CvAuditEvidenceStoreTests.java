package com.ttl.tabletennis.cv;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CvAuditEvidenceStoreTests {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void uploadForContradictionSnapshotsBufferAndDelegatesToUploader() {
        CvAuditFrameBuffer buffer = new CvAuditFrameBuffer(5);
        buffer.push("m1", Instant.parse("2026-05-17T10:00:00Z"), new byte[]{1});
        buffer.push("m1", Instant.parse("2026-05-17T10:00:01Z"), new byte[]{2});

        RecordingUploader uploader = new RecordingUploader(List.of(
                "s3://ttl-cv-audit/m1/20260517T1000/00.jpg",
                "s3://ttl-cv-audit/m1/20260517T1000/01.jpg"));
        CvAuditEvidenceStore store = new CvAuditEvidenceStore(buffer, uploader, objectMapper);

        List<String> refs = store.uploadForContradiction("m1", Instant.parse("2026-05-17T10:00:30Z"));

        assertEquals(2, refs.size());
        assertEquals(1, uploader.calls.size());
        assertEquals(2, uploader.calls.get(0).frames().size());
    }

    @Test
    void uploadForContradictionReturnsEmptyWhenUploaderDisabled() {
        CvAuditFrameBuffer buffer = new CvAuditFrameBuffer(5);
        buffer.push("m1", Instant.now(), new byte[]{1});
        CvAuditEvidenceStore store = new CvAuditEvidenceStore(buffer, new NoopCvAuditEvidenceUploader(), objectMapper);

        assertTrue(store.uploadForContradiction("m1", Instant.now()).isEmpty());
        assertEquals(false, store.isEnabled());
    }

    @Test
    void uploadForContradictionReturnsEmptyWhenBufferEmpty() {
        CvAuditEvidenceStore store = new CvAuditEvidenceStore(
                new CvAuditFrameBuffer(5), new RecordingUploader(List.of("ref")), objectMapper);

        assertTrue(store.uploadForContradiction("m1", Instant.now()).isEmpty());
    }

    @Test
    void uploadForContradictionReturnsEmptyForBlankMatch() {
        CvAuditEvidenceStore store = new CvAuditEvidenceStore(
                new CvAuditFrameBuffer(5), new RecordingUploader(List.of("ref")), objectMapper);

        assertTrue(store.uploadForContradiction(null, Instant.now()).isEmpty());
        assertTrue(store.uploadForContradiction("", Instant.now()).isEmpty());
    }

    @Test
    void uploadForContradictionSwallowsUploaderThrows() {
        CvAuditFrameBuffer buffer = new CvAuditFrameBuffer(5);
        buffer.push("m1", Instant.now(), new byte[]{1});
        CvAuditEvidenceUploader exploding = new CvAuditEvidenceUploader() {
            @Override
            public List<String> upload(String matchId, Instant at, List<CvAuditFrameBuffer.AuditFrame> frames) {
                throw new RuntimeException("boom");
            }
        };
        CvAuditEvidenceStore store = new CvAuditEvidenceStore(buffer, exploding, objectMapper);

        assertTrue(store.uploadForContradiction("m1", Instant.now()).isEmpty());
    }

    @Test
    void serializeRefsProducesJsonArray() {
        CvAuditEvidenceStore store = new CvAuditEvidenceStore(
                new CvAuditFrameBuffer(5), new NoopCvAuditEvidenceUploader(), objectMapper);
        Optional<String> json = store.serializeRefs(List.of("a", "b"));
        assertTrue(json.isPresent());
        assertEquals("[\"a\",\"b\"]", json.get());
    }

    @Test
    void serializeRefsReturnsEmptyForEmptyOrNull() {
        CvAuditEvidenceStore store = new CvAuditEvidenceStore(
                new CvAuditFrameBuffer(5), new NoopCvAuditEvidenceUploader(), objectMapper);
        assertTrue(store.serializeRefs(null).isEmpty());
        assertTrue(store.serializeRefs(List.of()).isEmpty());
    }

    @Test
    void constructorRejectsNullDeps() {
        assertThrows(IllegalArgumentException.class,
                () -> new CvAuditEvidenceStore(null, new NoopCvAuditEvidenceUploader(), objectMapper));
        assertThrows(IllegalArgumentException.class,
                () -> new CvAuditEvidenceStore(new CvAuditFrameBuffer(5), null, objectMapper));
        assertThrows(IllegalArgumentException.class,
                () -> new CvAuditEvidenceStore(new CvAuditFrameBuffer(5), new NoopCvAuditEvidenceUploader(), null));
    }

    private static final class RecordingUploader implements CvAuditEvidenceUploader {
        record Call(String matchId, Instant at, List<CvAuditFrameBuffer.AuditFrame> frames) { }

        final List<Call> calls = new ArrayList<>();
        private final List<String> refs;

        RecordingUploader(List<String> refs) {
            this.refs = refs;
        }

        @Override
        public List<String> upload(String matchId, Instant at, List<CvAuditFrameBuffer.AuditFrame> frames) {
            calls.add(new Call(matchId, at, frames));
            return refs;
        }
    }
}
