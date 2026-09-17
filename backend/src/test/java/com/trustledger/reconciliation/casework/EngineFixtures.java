package com.trustledger.reconciliation.casework;

import com.trustledger.reconciliation.casework.csv.CsvTable;
import com.trustledger.reconciliation.casework.engine.ReconciliationEngine;
import com.trustledger.reconciliation.casework.engine.ReconciliationEngine.FeeCheck;
import com.trustledger.reconciliation.casework.profile.ImportProfile;
import com.trustledger.reconciliation.casework.profile.RowRejected;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Builds canonical records for pure engine tests, from the ACME files or from inline CSV. */
final class EngineFixtures {
    private EngineFixtures() {}

    static final Instant PERIOD_END = Instant.parse("2026-09-01T00:00:00Z");

    /** provider-b, GBP, 150 bps, tolerance 0.01. Written out here so it does not come from production code. */
    static final ReconciliationEngine.FeeExpectation ACME_FEES = (provider, currency, at, gross) ->
        "provider-b".equals(provider) && "GBP".equals(currency)
            ? Optional.of(new FeeCheck(gross.multiply(new BigDecimal("150")).divide(new BigDecimal("10000"), 4, RoundingMode.HALF_EVEN), new BigDecimal("0.01")))
            : Optional.empty();

    static ReconciliationEngine.Config acmeConfig() {
        return new ReconciliationEngine.Config(2, ReconciliationEngine.Config.DEFAULT_COMPOSITE_WINDOW, PERIOD_END, ACME_FEES);
    }

    static ReconciliationEngine.Config noFees(int slaDays) {
        return new ReconciliationEngine.Config(slaDays, ReconciliationEngine.Config.DEFAULT_COMPOSITE_WINDOW, PERIOD_END,
            (p, c, at, g) -> Optional.empty());
    }

    static List<CanonicalRecord> acme() throws Exception {
        List<CanonicalRecord> all = new ArrayList<>();
        all.addAll(records("internal-expected", "acme-ledger", CaseworkHttp.fixture("internal-expected.csv")));
        all.addAll(records("provider-transactions", "provider-b", CaseworkHttp.fixture("provider-b-transactions.csv")));
        all.addAll(records("provider-transactions", "provider-a", CaseworkHttp.fixture("provider-a-transactions.csv")));
        all.addAll(records("provider-settlement", "provider-b", CaseworkHttp.fixture("provider-b-settlement.csv")));
        return all;
    }

    static List<CanonicalRecord> records(String profileName, String identity, String csv) {
        return records(profileName, identity, csv.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** Accepted rows only, keyed the way ImportService keys them (without tenant and case). */
    static List<CanonicalRecord> records(String profileName, String identity, byte[] csv) {
        ImportProfile profile = ImportProfile.forName(profileName);
        List<CanonicalRecord> out = new ArrayList<>();
        for (CsvTable.Row row : CsvTable.parse(csv).rows()) {
            try {
                CanonicalRecord r = profile.normalise(row.values(), row.number(), identity);
                out.add(r.withKey(Hashes.sha256(profileName, identity, String.valueOf(row.number()), row.raw())));
            } catch (RowRejected ignored) {
                // a rejected row never reaches the engine
            }
        }
        return out;
    }
}
