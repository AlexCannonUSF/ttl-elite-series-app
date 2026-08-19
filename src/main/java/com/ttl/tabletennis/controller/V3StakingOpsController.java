package com.ttl.tabletennis.controller;

import com.ttl.tabletennis.prediction.staking.StakingKillSwitch;
import com.ttl.tabletennis.prediction.staking.StakingPolicyCatalog;
import com.ttl.tabletennis.prediction.staking.StakingPolicyConfig;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDateTime;

/**
 * Operator-facing surface for Phase 06 item 2: read the active staking
 * policy snapshot, force a hot-reload, and toggle the kill-switch.
 */
@RestController
@RequestMapping("/api/v3/ops/staking")
public class V3StakingOpsController {

    private final StakingPolicyCatalog catalog;
    private final StakingKillSwitch killSwitch;

    public V3StakingOpsController(StakingPolicyCatalog catalog, StakingKillSwitch killSwitch) {
        this.catalog = catalog;
        this.killSwitch = killSwitch;
    }

    @GetMapping("/policy")
    public PolicyResponse policy() {
        StakingPolicyCatalog.Snapshot snapshot = catalog.snapshot();
        return new PolicyResponse(
                StakingPolicyCatalog.POLICY_NAME,
                snapshot.sourcePath(),
                snapshot.checksum(),
                snapshot.fileBacked(),
                snapshot.loadedAt(),
                snapshot.config()
        );
    }

    @PostMapping("/policy/reload")
    public PolicyResponse reloadNow(@RequestBody(required = false) ReloadRequest request) {
        String triggeredBy = request == null ? "ops" : request.triggeredBy();
        StakingPolicyCatalog.Snapshot snapshot = catalog.reloadNow(triggeredBy == null || triggeredBy.isBlank() ? "ops" : triggeredBy);
        return new PolicyResponse(
                StakingPolicyCatalog.POLICY_NAME,
                snapshot.sourcePath(),
                snapshot.checksum(),
                snapshot.fileBacked(),
                snapshot.loadedAt(),
                snapshot.config()
        );
    }

    @GetMapping("/kill-switch")
    public KillSwitchResponse killSwitchStatus() {
        StakingKillSwitch.Status status = killSwitch.status();
        return new KillSwitchResponse(
                killSwitch.isActive(),
                status.triggeredBy(),
                status.reason(),
                status.at()
        );
    }

    @PostMapping("/kill-switch/on")
    public KillSwitchResponse activate(@RequestBody(required = false) KillSwitchRequest body) {
        String triggeredBy = body == null ? "ops" : body.triggeredBy();
        String reason = body == null ? "manual" : body.reason();
        StakingKillSwitch.Status status = killSwitch.activate(triggeredBy, reason);
        return new KillSwitchResponse(true, status.triggeredBy(), status.reason(), status.at());
    }

    @PostMapping("/kill-switch/off")
    public KillSwitchResponse deactivate(@RequestBody(required = false) KillSwitchRequest body) {
        String triggeredBy = body == null ? "ops" : body.triggeredBy();
        String reason = body == null ? "manual" : body.reason();
        StakingKillSwitch.Status status = killSwitch.deactivate(triggeredBy, reason);
        return new KillSwitchResponse(false, status.triggeredBy(), status.reason(), status.at());
    }

    public record ReloadRequest(String triggeredBy) { }

    public record KillSwitchRequest(String triggeredBy, String reason) { }

    public record PolicyResponse(String policyName,
                                  String sourcePath,
                                  String checksum,
                                  boolean fileBacked,
                                  LocalDateTime loadedAt,
                                  StakingPolicyConfig config) { }

    public record KillSwitchResponse(boolean active,
                                      String triggeredBy,
                                      String reason,
                                      Instant at) { }
}
