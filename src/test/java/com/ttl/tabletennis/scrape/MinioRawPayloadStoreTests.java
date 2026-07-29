package com.ttl.tabletennis.scrape;

import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class MinioRawPayloadStoreTests {

    @Test
    void putUploadsGzippedBodyAndReturnsCanonicalRef() throws Exception {
        MinioClient client = mock(MinioClient.class);
        MinioRawPayloadStore store = new MinioRawPayloadStore(client, "ttl-raw");
        byte[] body = "{\"hello\":\"world\"}".getBytes();

        String ref = store.put(
                SourceId.HR_MKT,
                "corr-abc",
                Instant.parse("2026-05-17T03:04:05Z"),
                body
        );

        assertEquals("s3://ttl-raw/HR_MKT/2026-05-17/corr-abc", ref);

        ArgumentCaptor<PutObjectArgs> captor = ArgumentCaptor.forClass(PutObjectArgs.class);
        verify(client).putObject(captor.capture());
        PutObjectArgs args = captor.getValue();
        assertEquals("ttl-raw", args.bucket());
        assertEquals("HR_MKT/2026-05-17/corr-abc.json.gz", args.object());
        assertEquals(MinioRawPayloadStore.CONTENT_TYPE, args.contentType());
        assertArrayEquals(body, ungzip(args.stream().readAllBytes()));
    }

    @Test
    void putSanitizesAndLowercasesCorrelationId() throws Exception {
        MinioClient client = mock(MinioClient.class);
        MinioRawPayloadStore store = new MinioRawPayloadStore(client, "ttl-raw");

        String ref = store.put(
                SourceId.STREAM_CV,
                "Frame#42:match 7/odd",
                Instant.parse("2026-05-17T03:04:05Z"),
                new byte[]{1, 2, 3}
        );

        assertEquals("s3://ttl-raw/STREAM_CV/2026-05-17/frame_42_match_7_odd", ref);

        ArgumentCaptor<PutObjectArgs> captor = ArgumentCaptor.forClass(PutObjectArgs.class);
        verify(client).putObject(captor.capture());
        assertEquals("STREAM_CV/2026-05-17/frame_42_match_7_odd.json.gz", captor.getValue().object());
    }

    @Test
    void putWrapsMinioFailuresInRawPayloadStoreException() throws Exception {
        MinioClient client = mock(MinioClient.class);
        doThrow(new RuntimeException("boom")).when(client).putObject(any(PutObjectArgs.class));
        MinioRawPayloadStore store = new MinioRawPayloadStore(client, "ttl-raw");

        RawPayloadStoreException ex = assertThrows(RawPayloadStoreException.class, () -> store.put(
                SourceId.HR_MKT,
                "corr-1",
                Instant.parse("2026-05-17T03:04:05Z"),
                new byte[]{0x42}
        ));
        assertEquals(RuntimeException.class, ex.getCause().getClass());
    }

    @Test
    void putRejectsBlankCorrelationIdAndNullArgs() {
        MinioClient client = mock(MinioClient.class);
        MinioRawPayloadStore store = new MinioRawPayloadStore(client, "ttl-raw");

        assertThrows(IllegalArgumentException.class,
                () -> store.put(null, "corr", Instant.now(), new byte[0]));
        assertThrows(IllegalArgumentException.class,
                () -> store.put(SourceId.HR_MKT, "", Instant.now(), new byte[0]));
        assertThrows(IllegalArgumentException.class,
                () -> store.put(SourceId.HR_MKT, "corr", Instant.now(), null));
    }

    @Test
    void constructorRejectsNullClientAndBlankBucket() {
        assertThrows(IllegalArgumentException.class,
                () -> new MinioRawPayloadStore(null, "ttl-raw"));
        assertThrows(IllegalArgumentException.class,
                () -> new MinioRawPayloadStore(mock(MinioClient.class), ""));
    }

    @Test
    void sanitizeCorrelationIdTruncatesLongInput() {
        String input = "x".repeat(200);
        String safe = MinioRawPayloadStore.sanitizeCorrelationId(input);
        assertEquals(MinioRawPayloadStore.MAX_CORRELATION_ID_LENGTH, safe.length());
    }

    private static byte[] ungzip(byte[] gzipped) throws IOException {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(gzipped))) {
            return in.readAllBytes();
        }
    }
}
