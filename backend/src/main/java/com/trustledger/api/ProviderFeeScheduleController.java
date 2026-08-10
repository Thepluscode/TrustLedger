package com.trustledger.api;

import com.trustledger.app.AccessControlService;
import com.trustledger.app.ProviderFeeScheduleService;
import com.trustledger.persistence.entity.ProviderFeeScheduleEntity;
import com.trustledger.security.CurrentUser;
import com.trustledger.security.Permission;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

/**
 * Contracted provider fee schedules — what settlement reconciliation checks provider fees against.
 * Managing them is a provider-configuration action (PROVIDER_CONFIG_MANAGE); they are temporal, so
 * recording a new one supersedes the previous from its effective instant without erasing history.
 */
@RestController
@RequestMapping("/api/v1/tenant/fee-schedules")
public class ProviderFeeScheduleController {

    private final ProviderFeeScheduleService schedules;
    private final AccessControlService access;

    public ProviderFeeScheduleController(ProviderFeeScheduleService schedules, AccessControlService access) {
        this.schedules = schedules;
        this.access = access;
    }

    public record FeeScheduleRequest(String provider, String currency, int percentageBps, BigDecimal flatFee,
                                     BigDecimal feeCap, BigDecimal tolerance, Instant effectiveFrom) {}

    public record FeeScheduleView(UUID id, String provider, String currency, int percentageBps, String flatFee,
                                  String feeCap, String tolerance, Instant effectiveFrom, Instant createdAt) {}

    @PostMapping
    public FeeScheduleView record(@RequestBody FeeScheduleRequest body) {
        access.require(Permission.PROVIDER_CONFIG_MANAGE);
        return view(schedules.record(CurrentUser.tenantId(), CurrentUser.userId(), body.provider(), body.currency(),
                body.percentageBps(), body.flatFee(), body.feeCap(), body.tolerance(), body.effectiveFrom()));
    }

    @GetMapping
    public List<FeeScheduleView> list() {
        access.require(Permission.PROVIDER_CONFIG_MANAGE);
        return schedules.list(CurrentUser.tenantId()).stream().map(ProviderFeeScheduleController::view).toList();
    }

    private static FeeScheduleView view(ProviderFeeScheduleEntity s) {
        return new FeeScheduleView(s.getId(), s.getProvider(), s.getCurrency(), s.getPercentageBps(),
                s.getFlatFee().toPlainString(), s.getFeeCap() == null ? null : s.getFeeCap().toPlainString(),
                s.getTolerance().toPlainString(), s.getEffectiveFrom(), s.getCreatedAt());
    }
}
