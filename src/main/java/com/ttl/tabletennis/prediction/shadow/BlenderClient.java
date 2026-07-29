package com.ttl.tabletennis.prediction.shadow;

import java.time.Duration;
import java.util.Optional;

public interface BlenderClient {

    Result score(BlenderRequest request, Duration timeout);

    default boolean isEnabled() {
        return true;
    }

    enum Status {
        OK,
        DISABLED,
        SCHEMA_HASH_MISMATCH,
        SERVICE_UNAVAILABLE,
        ERROR
    }

    /**
     * Result envelope. ``response`` is populated only when ``status == OK``.
     * Every code path returns a result (even the failure paths) so the
     * shadow service can persist a diff row that reflects what actually
     * happened.
     */
    record Result(Status status,
                  Optional<BlenderResponse> response,
                  String reason,
                  long latencyMs) {

        public Result {
            if (status == null) {
                throw new IllegalArgumentException("status must not be null");
            }
            response = response == null ? Optional.empty() : response;
            reason = reason == null ? "" : reason;
            if (latencyMs < 0L) {
                latencyMs = 0L;
            }
        }

        public boolean isOk() {
            return status == Status.OK;
        }

        public static Result ok(BlenderResponse response, long latencyMs) {
            return new Result(Status.OK, Optional.of(response), "", latencyMs);
        }

        public static Result disabled(String reason) {
            return new Result(Status.DISABLED, Optional.empty(), reason, 0L);
        }

        public static Result schemaHashMismatch(String reason, long latencyMs) {
            return new Result(Status.SCHEMA_HASH_MISMATCH, Optional.empty(), reason, latencyMs);
        }

        public static Result serviceUnavailable(String reason, long latencyMs) {
            return new Result(Status.SERVICE_UNAVAILABLE, Optional.empty(), reason, latencyMs);
        }

        public static Result error(String reason, long latencyMs) {
            return new Result(Status.ERROR, Optional.empty(), reason, latencyMs);
        }
    }
}
