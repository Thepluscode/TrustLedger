package com.trustledger.persistence.repo;

/** Aggregate projection: how often a signal type fired for a tenant, and its total score contribution. */
public interface FraudSignalFrequency {
    String getSignalType();
    long getOccurrences();
    long getTotalScoreDelta();
}
