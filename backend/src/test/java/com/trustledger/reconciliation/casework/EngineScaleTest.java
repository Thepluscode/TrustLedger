package com.trustledger.reconciliation.casework;

import static org.junit.jupiter.api.Assertions.*;

import com.trustledger.reconciliation.casework.engine.ReconciliationEngine;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A measurement, with a bound loose enough that only a complexity regression trips it. The engine groups
 * by key and sorts, so it should be O(n log n): 100k records in well under the bound on a laptop. A pairwise
 * scan would be 10^10 comparisons and would not finish.
 */
class EngineScaleTest {

    private static List<CanonicalRecord> generated(int payments) {
        StringBuilder internal = new StringBuilder("internal_ref,provider,provider_ref,event_type,currency,amount,expected_at,status\n");
        StringBuilder provider = new StringBuilder("event_id,transaction_ref,merchant_ref,event_type,status,currency,gross,fee,net,occurred_at,received_at\n");
        for (int i = 0; i < payments; i++) {
            internal.append("INT-").append(i).append(",provider-a,TX-").append(i).append(",PAYMENT,NGN,100.00,2026-08-05T10:00:00Z,PAID\n");
            // Every 100th payment never reaches the provider.
            if (i % 100 != 0) {
                provider.append("evt-").append(i).append(",TX-").append(i).append(",INT-").append(i)
                    .append(",CHARGE,SUCCESS,NGN,100.00,0.00,100.00,2026-08-05T10:00:01Z,2026-08-05T10:00:02Z\n");
            }
        }
        List<CanonicalRecord> all = new java.util.ArrayList<>(EngineFixtures.records("internal-expected", "acme-ledger", internal.toString()));
        all.addAll(EngineFixtures.records("provider-transactions", "provider-a", provider.toString()));
        return all;
    }

    private static void measure(int payments, long boundMillis) {
        List<CanonicalRecord> records = generated(payments);
        long t0 = System.nanoTime();
        ReconciliationEngine.Result r = ReconciliationEngine.reconcile(records, EngineFixtures.noFees(2));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        System.out.println("engine-scale records=" + records.size() + " ms=" + ms);
        int missing = (payments + 99) / 100;
        // The fixture helper drops rejected rows silently; a generator typo must not pass as a small input.
        assertEquals(2 * payments - missing, records.size());
        assertEquals(payments, r.internalPayments());
        assertEquals(payments - missing, r.internalMatched());
        assertEquals(missing, r.findings().stream().filter(f -> f.type().equals("MISSING_PROVIDER_RECORD")).count());
        assertTrue(ms < boundMillis, records.size() + " records took " + ms + " ms");
    }

    @Test
    void tenThousandRecords() { measure(5_000, 10_000); }

    @Test
    void twoHundredThousandRecords() /* 199,000 */ { measure(100_000, 60_000); }
}
