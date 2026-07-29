package com.ttl.tabletennis.cv;

import java.time.Instant;
import java.util.List;

public class NoopCvAuditEvidenceUploader implements CvAuditEvidenceUploader {

    @Override
    public List<String> upload(String matchId, Instant contradictionAtUtc, List<CvAuditFrameBuffer.AuditFrame> frames) {
        return List.of();
    }

    @Override
    public boolean isEnabled() {
        return false;
    }
}
