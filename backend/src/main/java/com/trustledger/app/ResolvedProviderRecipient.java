package com.trustledger.app;

import java.util.UUID;

/** In-memory execution address. The provider token must never be written to audit or attempt evidence. */
public record ResolvedProviderRecipient(
    UUID payoutInstrumentId,
    UUID providerRecipientMappingId,
    String providerRecipientCode
) {}