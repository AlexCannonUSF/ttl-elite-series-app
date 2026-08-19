package com.ttl.tabletennis.cv;

import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class MinioCvAuditEvidenceUploaderTests {

    @Test
    void uploadWritesEveryFrameAndReturnsRefs() throws Exception {
        MinioClient client = mock(MinioClient.class);
        MinioCvAuditEvidenceUploader uploader = new MinioCvAuditEvidenceUploader(client, "ttl-cv-audit");
        List<CvAuditFrameBuffer.AuditFrame> frames = List.of(
                new CvAuditFrameBuffer.AuditFrame(Instant.parse("2026-05-17T10:00:00Z"), new byte[]{1, 2}),
                new CvAuditFrameBuffer.AuditFrame(Instant.parse("2026-05-17T10:00:01Z"), new byte[]{3, 4})
        );

        List<String> refs = uploader.upload("match-1", Instant.parse("2026-05-17T10:00:30Z"), frames);

        assertEquals(2, refs.size());
        assertEquals("s3://ttl-cv-audit/match-1/20260517T1000/00.jpg", refs.get(0));
        assertEquals("s3://ttl-cv-audit/match-1/20260517T1000/01.jpg", refs.get(1));

        ArgumentCaptor<PutObjectArgs> captor = ArgumentCaptor.forClass(PutObjectArgs.class);
        verify(client, org.mockito.Mockito.times(2)).putObject(captor.capture());
        List<PutObjectArgs> calls = captor.getAllValues();
        assertEquals("ttl-cv-audit", calls.get(0).bucket());
        assertEquals("match-1/20260517T1000/00.jpg", calls.get(0).object());
        assertEquals(MinioCvAuditEvidenceUploader.CONTENT_TYPE, calls.get(0).contentType());
    }

    @Test
    void uploadSanitizesMatchIdInKey() throws Exception {
        MinioClient client = mock(MinioClient.class);
        MinioCvAuditEvidenceUploader uploader = new MinioCvAuditEvidenceUploader(client, "ttl-cv-audit");

        List<String> refs = uploader.upload(
                "Match!@#$%/123",
                Instant.parse("2026-05-17T10:00:30Z"),
                List.of(new CvAuditFrameBuffer.AuditFrame(Instant.parse("2026-05-17T10:00:00Z"), new byte[]{1}))
        );

        assertEquals(1, refs.size());
        assertTrue(refs.get(0).startsWith("s3://ttl-cv-audit/Match_123/"));
    }

    @Test
    void uploadIsNoopForBlankMatchOrEmptyFrames() {
        MinioClient client = mock(MinioClient.class);
        MinioCvAuditEvidenceUploader uploader = new MinioCvAuditEvidenceUploader(client, "ttl-cv-audit");

        assertTrue(uploader.upload(null, Instant.now(), List.of()).isEmpty());
        assertTrue(uploader.upload("", Instant.now(), List.of()).isEmpty());
        assertTrue(uploader.upload("m", Instant.now(), null).isEmpty());
        assertTrue(uploader.upload("m", Instant.now(), List.of()).isEmpty());
    }

    @Test
    void uploadSwallowsPerFrameFailuresAndReturnsPartial() throws Exception {
        MinioClient client = mock(MinioClient.class);
        doThrow(new RuntimeException("first frame down"))
                .doReturn(null)
                .when(client).putObject(any(PutObjectArgs.class));
        MinioCvAuditEvidenceUploader uploader = new MinioCvAuditEvidenceUploader(client, "ttl-cv-audit");

        List<String> refs = uploader.upload("m1", Instant.parse("2026-05-17T10:00:30Z"), List.of(
                new CvAuditFrameBuffer.AuditFrame(Instant.now(), new byte[]{1}),
                new CvAuditFrameBuffer.AuditFrame(Instant.now(), new byte[]{2})
        ));

        assertEquals(1, refs.size());
        assertTrue(refs.get(0).endsWith("/01.jpg"));
    }

    @Test
    void uploadSkipsBlankFrames() throws Exception {
        MinioClient client = mock(MinioClient.class);
        MinioCvAuditEvidenceUploader uploader = new MinioCvAuditEvidenceUploader(client, "ttl-cv-audit");

        List<String> refs = uploader.upload("m1", Instant.parse("2026-05-17T10:00:30Z"), java.util.Arrays.asList(
                new CvAuditFrameBuffer.AuditFrame(Instant.now(), null),
                new CvAuditFrameBuffer.AuditFrame(Instant.now(), new byte[0]),
                new CvAuditFrameBuffer.AuditFrame(Instant.now(), new byte[]{9})
        ));

        assertEquals(1, refs.size());
        assertTrue(refs.get(0).endsWith("/02.jpg"));
    }

    @Test
    void constructorRejectsNullClientAndBlankBucket() {
        assertThrows(IllegalArgumentException.class, () -> new MinioCvAuditEvidenceUploader(null, "b"));
        assertThrows(IllegalArgumentException.class, () -> new MinioCvAuditEvidenceUploader(mock(MinioClient.class), ""));
    }
}
