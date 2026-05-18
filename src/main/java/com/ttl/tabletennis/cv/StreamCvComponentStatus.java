package com.ttl.tabletennis.cv;

public record StreamCvComponentStatus(
        String component,
        String rolloutState,
        boolean enabled,
        String detail
) {
}
