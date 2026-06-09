package com.trustledger.api;

import com.trustledger.api.ApiViews.*;
import com.trustledger.persistence.entity.BeneficiaryEntity;
import com.trustledger.persistence.repo.BeneficiaryRepository;
import com.trustledger.security.CurrentUser;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/beneficiaries")
public class BeneficiaryController {

    private final BeneficiaryRepository beneficiaries;

    public BeneficiaryController(BeneficiaryRepository beneficiaries) {
        this.beneficiaries = beneficiaries;
    }

    @PostMapping
    public BeneficiaryView create(@RequestBody CreateBeneficiaryRequest req) {
        if (req.name() == null || req.name().isBlank()) throw new IllegalArgumentException("name is required");
        if (req.destinationAccountId() == null) throw new IllegalArgumentException("destinationAccountId is required");
        BeneficiaryEntity b = beneficiaries.save(new BeneficiaryEntity(UUID.randomUUID(), CurrentUser.tenantId(),
            CurrentUser.userId(), req.name(), req.destinationAccountId(), false));
        return view(b);
    }

    @GetMapping
    public List<BeneficiaryView> list() {
        return beneficiaries.findByTenantId(CurrentUser.tenantId()).stream().map(BeneficiaryController::view).toList();
    }

    private static BeneficiaryView view(BeneficiaryEntity b) {
        return new BeneficiaryView(b.getId(), b.getName(), b.getDestinationAccountId(), b.isTrusted());
    }
}
