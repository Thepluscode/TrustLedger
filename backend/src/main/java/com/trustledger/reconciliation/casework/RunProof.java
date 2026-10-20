package com.trustledger.reconciliation.casework;

import com.trustledger.persistence.entity.ProviderFeeScheduleEntity;
import com.trustledger.reconciliation.casework.CaseworkStore.CaseRow;
import com.trustledger.reconciliation.casework.CaseworkStore.ImportRow;
import com.trustledger.reconciliation.casework.CaseworkStore.RunRow;
import com.trustledger.reconciliation.casework.CaseworkStore.StoredRecord;
import com.trustledger.reconciliation.casework.engine.ReconciliationEngine.Finding;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Values read and findings produced by one run, captured before any later input can change. */
final class RunProof {

    private RunProof() {}

    static Map<String, Object> snapshot(RunRow run, CaseRow c, List<ImportRow> imports,
                                        List<ProviderFeeScheduleEntity> schedules, List<Finding> findings,
                                        Map<String, StoredRecord> byKey) {
        Map<String, Object> proof = new LinkedHashMap<>();
        proof.put("version", "recon-run-proof/1");
        proof.put("runId", run.id().toString());
        proof.put("runKey", run.runKey());
        proof.put("rulesetVersion", run.rulesetVersion());
        proof.put("recordsProcessed", run.recordsProcessed());
        proof.put("exceptionCount", run.exceptionCount());
        proof.put("completedAt", run.completedAt().toString());
        proof.put("caseId", c.id().toString());
        proof.put("periodEnd", c.periodEnd().toString());
        proof.put("settlementSlaDays", c.settlementSlaDays());
        List<Map<String, Object>> sourceList = new ArrayList<>();
        for (ImportRow i : imports.stream().sorted(Comparator.comparing(r -> r.id().toString())).toList()) {
            Map<String, Object> source = new LinkedHashMap<>();
            source.put("importId", i.id().toString());
            source.put("sourceType", i.sourceType());
            source.put("sourceIdentity", i.sourceIdentity());
            source.put("fileSha256", i.fileSha256());
            source.put("byteSize", i.byteSize());
            source.put("evidenceStorageKey", i.storageKey());
            source.put("profile", i.profile());
            source.put("profileVersion", i.profileVersion());
            sourceList.add(source);
        }
        proof.put("sources", sourceList);
        proof.put("feeSchedules", feeSchedules(schedules));
        List<Map<String, Object>> found = new ArrayList<>();
        for (Finding f : findings) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("type", f.type());
            item.put("severity", f.severity());
            item.put("ruleId", f.ruleId());
            item.put("exposureCurrency", f.exposure() == null ? null : f.currency());
            item.put("exposureAmount", plain(f.exposure()));
            item.put("expected", f.expected());
            item.put("actual", f.actual());
            List<Map<String, Object>> refs = new ArrayList<>();
            for (String key : f.recordKeys().stream().sorted().toList()) {
                StoredRecord r = byKey.get(key);
                if (r == null) throw new IllegalStateException("finding names an unknown record: " + key);
                Map<String, Object> ref = new LinkedHashMap<>();
                ref.put("recordKey", key);
                ref.put("importId", r.importId().toString());
                ref.put("rowNumber", r.record().rowNumber());
                ref.put("fileSha256", r.evidenceFileSha256());
                ref.put("rowSha256", r.evidenceRowSha256());
                refs.add(ref);
            }
            item.put("sourceRows", refs);
            found.add(item);
        }
        found.sort(Comparator.comparing((Map<String, Object> m) -> (String) m.get("type"))
            .thenComparing(m -> (String) m.get("ruleId"))
            .thenComparing(m -> m.get("sourceRows").toString()));
        proof.put("findings", found);
        return proof;
    }

    static List<Map<String, Object>> feeSchedules(List<ProviderFeeScheduleEntity> schedules) {
        List<ProviderFeeScheduleEntity> ordered = new ArrayList<>(schedules);
        ordered.sort(Comparator.comparing(ProviderFeeScheduleEntity::getProvider)
            .thenComparing(ProviderFeeScheduleEntity::getCurrency)
            .thenComparing(ProviderFeeScheduleEntity::getEffectiveFrom));
        List<Map<String, Object>> values = new ArrayList<>();
        for (ProviderFeeScheduleEntity s : ordered) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("scheduleId", s.getId().toString());
            item.put("provider", s.getProvider());
            item.put("currency", s.getCurrency());
            item.put("percentageBps", s.getPercentageBps());
            item.put("flatFee", plain(s.getFlatFee()));
            item.put("feeCap", plain(s.getFeeCap()));
            item.put("tolerance", plain(s.getTolerance()));
            item.put("effectiveFrom", s.getEffectiveFrom().toString());
            values.add(item);
        }
        return values;
    }

    private static String plain(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }
}
