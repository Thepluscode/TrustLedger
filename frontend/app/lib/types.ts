export interface AuthResponse {
  token: string | null;
  tenantId: string;
  userId: string;
  role: string;
  email: string;
}

export interface AccountView {
  id: string;
  currency: string;
  status: string;
  availableBalance: string;
  pendingBalance: string;
  postedBalance: string;
}

export interface TransferResponse {
  transactionId: string;
  status: string;
  riskScore: number;
  decision: string;
  message: string;
}

export interface ExternalPaymentResponse {
  transactionId: string;
  providerReference: string | null;
  status: string;
  riskScore: number;
  decision: string;
  message: string;
}

export interface AssessResponse {
  riskScore: number;
  decision: string;
  signals: string[];
}

export interface FraudCaseView {
  id: string;
  transactionId: string;
  status: string;
  severity: string;
  riskScore: number;
}

export interface FraudSignalFrequency {
  signalType: string;
  occurrences: number;
  totalScoreDelta: number;
}

export interface FraudSignalDetail {
  signalType: string;
  scoreDelta: number;
  severity: string;
  reason: string;
  evidence: string;
  createdAt: string;
}

export interface DashboardSummary {
  accounts: number;
  transfersCompleted: number;
  transfersHeld: number;
  transfersRejected: number;
  fraudCasesOpen: number;
  reconciliationIssuesOpen: number;
}

export interface BeneficiaryView {
  id: string;
  name: string;
  destinationAccountId: string;
  trusted: boolean;
}

export interface EvidenceExportView {
  id: string;
  resourceType: string;
  resourceId: string;
  format: string;
  byteSize: number;
  checksum: string;
}

export interface LedgerEntryView {
  id: string;
  ledgerTransactionId: string;
  accountId: string;
  direction: "DEBIT" | "CREDIT";
  amount: string;
  currency: string;
  entryType: string;
}

export interface LedgerTransactionView {
  id: string;
  type: string;
  status: string;
  currency: string;
  entries: LedgerEntryView[];
}

export interface AuditLogView {
  id: string;
  actorType: string;
  actorId: string | null;
  action: string;
  resourceType: string;
  resourceId: string | null;
  /** Null for rows written off-request (workers, sweeps) — those have no request to correlate to. */
  correlationId: string | null;
  createdAt: string;
}

export interface FraudPolicy {
  monitor: number;
  mfa: number;
  hold: number;
  reject: number;
  deviceTrustAfter: number;
  autoFreezeEnabled: boolean;
}

export interface TransferListItem {
  id: string;
  sourceAccountId: string;
  destinationAccountId: string;
  beneficiaryId: string | null;
  amount: string;
  currency: string;
  status: string;
  riskScore: number;
  fraudDecision: string;
  channel: string;
  reference: string | null;
  createdAt: string;
}

export interface TransferDetail {
  transfer: TransferListItem;
  fraudCase: FraudCaseView | null;
  ledger: LedgerTransactionView[];
  auditTrail: AuditLogView[];
}

export interface DeviceProfile {
  id: string;
  userId: string;
  deviceId: string;
  trusted: boolean;
  transferCount: number;
  riskScore: number;
  country: string | null;
  lastSeenAt: string | null;
}

export interface BeneficiaryProfile {
  id: string;
  beneficiaryAccountId: string;
  totalTransfers: number;
  distinctSenders: number;
  totalAmountReceived: string;
  confirmedFraudLinked: boolean;
  riskScore: number;
  firstTransferAt: string | null;
}

export interface UserProfile {
  userId: string;
  medianTransferAmount: string;
  maxNormalTransferAmount: string;
  transferCount: number;
  riskLevel: string;
  lastPasswordChangeAt: string | null;
}

export interface SettlementStatement {
  id: string;
  provider: string;
  currency: string;
  statementRef: string;
  periodStart: string;
  periodEnd: string;
  lineCount: number;
  totalAmount: string;
  totalFees: string;
  ingestedAt: string;
}

export interface SettlementIngestResult {
  statement: SettlementStatement;
  alreadyIngested: boolean;
  matched: number;
  unmatched: number;
  amountMismatch: number;
  missing: number;
}

export interface SettlementLine {
  providerReference: string;
  amount: string;
  fee: string;
  status: string;
  matchStatus: string;
  matchedAttemptId: string | null;
}

export interface SettlementStatementDetail {
  statement: SettlementStatement;
  lines: SettlementLine[];
  reconciliationIssueIds: string[];
}

export interface ReconciliationIssue {
  id: string;
  severity: string;
  type: string;
  entityType: string;
  entityId: string;
  expectedState: string | null;
  actualState: string | null;
  evidence: string;
  status: string;
  createdAt: string;
  resolvedAt: string | null;
  ownerUserId: string | null;
  /** Money at risk. null means this break type carries no amount — render "—", never 0. */
  exposureAmount: string | null;
  exposureCurrency: string | null;
  dueAt: string;
  /** Working state. `status` stays the coarse OPEN/RESOLVED flag. */
  lifecycleState: string;
  caseId: string | null;
  runId: string | null;
  ruleId: string | null;
  ruleVersion: string | null;
  reasonCode: string | null;
  resolutionNote: string | null;
  resolutionEvidenceRef: string | null;
  resolvedBy: string | null;
  /** Echo back as expectedVersion so a stale tab is refused instead of overwriting someone's work. */
  version: number;
}

export interface ReconIssueActivity {
  seq: number;
  kind: string;
  fromState: string | null;
  toState: string | null;
  actorId: string | null;
  body: string | null;
  evidenceStorageKey: string | null;
  evidenceSha256: string | null;
  evidenceFilename: string | null;
  createdAt: string | null;
}

export interface ReconCase {
  id: string;
  caseRef: string;
  title: string;
  periodStart: string;
  periodEnd: string;
  settlementSlaDays: number;
  status: string;
  createdAt: string;
}

export interface ReconImportManifest {
  id: string;
  sourceType: string;
  sourceIdentity: string;
  originalFilename: string;
  fileSha256: string;
  byteSize: number;
  profile: string;
  profileVersion: number;
  status: string;
  failureReason: string | null;
  recordCount: number;
  acceptedCount: number;
  rejectedCount: number;
  duplicateCount: number;
  rejectionsAcknowledgedBy: string | null;
  actorId: string;
  correlationId: string | null;
  importedAt: string;
  /** How many times these exact bytes arrived. 1 for a file; a redelivered event counts up. */
  deliveryCount: number;
  feedId: string | null;
}

export interface ReconFeed {
  id: string;
  caseId: string;
  providerIdentity: string;
  profile: string;
  status: string;
  createdAt: string;
  revokedAt: string | null;
}

/** `token` is present only in the creation response and never again. */
export interface ReconFeedCreated { feed: ReconFeed; token: string; deliveryPath: string }

export interface ReconCurrencyTotal { currency: string; grossTotal: string; rowCount: number }

export interface ReconProviderSummary {
  settlementRecords: number;
  rowsNotReconciled: number;
  notReconciledByReason: Record<string, number>;
  rowsWithUnresolvedTime: number;
}

export interface ReconProviderTime { raw: string; source: string; zoneEvidence: string | null; instant: string | null; unresolvedReason: string | null }

export interface ReconProviderComponent { role: string; direction: string; currency: string; providerField: string; rawValue: string }

export interface ReconProviderRow {
  rowNumber: number;
  identity: string;
  paymentRef: string | null;
  kind: string;
  recordKey: string | null;
  notReconciledReason: string | null;
  evidence: { occurredAt: ReconProviderTime | null; bookedAt: ReconProviderTime | null; components: ReconProviderComponent[] };
}

export interface ReconImportView { manifest: ReconImportManifest; currencyTotals: ReconCurrencyTotal[]; provider?: ReconProviderSummary | null }

export interface ReconCaseView { reconciliationCase: ReconCase; imports: ReconImportView[]; blockers: string[] }

/** A source time as evidence. `instant` is null when nothing establishes it; `raw` is then all that is known. */
export interface TimelineTime { raw: string | null; source: string; zoneEvidence: string | null; instant: string | null; unresolvedReason: string | null }

/** One amount in one currency. `value` is exact decimal text and is never reformatted or summed across currencies. */
export interface TimelineAmount { role: string; currency: string; value: string; direction: string | null; providerField: string | null; rawValue: string | null }

export interface TimelineEvidence {
  importId: string;
  sourceType: string | null;
  sourceIdentity: string | null;
  filename: string | null;
  profile: string | null;
  fileSha256: string | null;
  rowNumber: number;
  rowSha256: string;
  storageKey: string | null;
  rawRow: string | null;
  /** false when the stored row no longer hashes to what was recorded at import. */
  intact: boolean;
  deliveryCount: number;
  feedId: string | null;
  importedAt: string | null;
  inLatestRun: boolean;
}

export interface TimelineEvent {
  eventId: string;
  position: number;
  kind: string;
  eventType: string | null;
  provider: string | null;
  status: string | null;
  refs: { providerEventId: string | null; stableRef: string | null; internalRef: string | null; settlementBatch: string | null };
  occurred: TimelineTime | null;
  booked: TimelineTime | null;
  receivedAt: string | null;
  placement: "BY_SOURCE_TIME" | "UNPLACED";
  arrivedOutOfOrder: boolean;
  amounts: TimelineAmount[];
  fxRate: string | null;
  role: string;
  roleReason: string | null;
  duplicateOf: string | null;
  linkedBy: string | null;
  derivedInto: string | null;
  matches: { ruleId: string; ruleVersion: string; counterpartEventId: string }[];
  findingIds: string[];
  evidence: TimelineEvidence;
}

export interface TimelineHistoryEntry { seq: number; kind: string; fromState: string | null; toState: string | null; actorId: string | null; body: string | null; evidenceSha256: string | null; evidenceFilename: string | null; at: string | null }

export interface TimelineFinding {
  exceptionId: string;
  type: string;
  classification: string;
  severity: string;
  expected: string | null;
  actual: string | null;
  explanation: string | null;
  exposureAmount: string | null;
  exposureCurrency: string | null;
  ruleId: string | null;
  ruleVersion: string | null;
  raisedByRunId: string | null;
  raisedByLatestRun: boolean;
  raisedAt: string;
  open: boolean;
  lifecycleState: string;
  ownerUserId: string | null;
  dueAt: string | null;
  decision: { closedAs: string; reasonCode: string; explanation: string | null; decidedBy: string | null; decidedAt: string | null } | null;
  eventIds: string[];
  history: TimelineHistoryEntry[];
}

export interface PaymentTimelineView {
  payment: { ref: string; providers: string[]; stableRefs: string[]; internalRefs: string[]; currencies: string[] };
  conclusion: {
    state: string;
    statement: string;
    openFindingTypes: string[];
    decidedFindingTypes: string[];
    openExposureByCurrency: { currency: string; amount: string }[];
    notes: { code: string; detail: string }[];
    basis: { runId: string; runKey: string; rulesetVersion: string; completedAt: string } | null;
  };
  timeline: TimelineEvent[];
  findings: TimelineFinding[];
}

export interface ReconSourceRow {
  rowNumber: number;
  rawRow: string;
  status: string;
  rejectionCode: string | null;
  rejectionMessage: string | null;
}

export interface ReconRun {
  id: string;
  runKey: string;
  rulesetVersion: string;
  recordsProcessed: number;
  internalPayments: number;
  internalMatched: number;
  rejectedInputs: number;
  exceptionCount: number;
  /** JSON: exceptionsByType, matchesByRule, settlementCoveredProviders, providersWithoutSettlementFile. */
  summary: string;
  startedAt: string;
  completedAt: string;
}

export interface ReconRunView {
  run: ReconRun;
  unresolvedByCurrency: { currency: string; unresolvedAmount: string }[];
  matchRate: string | null;
  replayed: boolean;
}

export interface ReconBundle {
  exportId: string;
  bundleStatus: string;
  contentHash: string;
  fileChecksum: string;
  byteSize: number;
  signed: boolean;
}

export interface ReconciliationAuditEntry {
  action: string;
  actorId: string | null;
  at: string;
  metadata: string;
}

export interface ReconciliationListSummary {
  total: number;
  open: number;
  criticalOpen: number;
  resolved: number;
  overdueOpen: number;
  /** Keyed by currency and never totalled: one sum across currencies would be meaningless. */
  openExposureByCurrency: Record<string, string>;
}

export interface ReconciliationIssueList {
  items: ReconciliationIssue[];
  summary: ReconciliationListSummary;
}

export interface TeamMember {
  id: string;
  email: string;
  role: string;
  createdAt: string;
}

export interface OrgUnit {
  id: string;
  parentUnitId: string | null;
  name: string;
  type: string;
}

/** The signed-in user's own org-unit scope. `scoped: false` (empty units) = tenant-wide. */
export interface MyScope {
  scoped: boolean;
  units: OrgUnit[];
}

export interface InvitedUser {
  id: string;
  email: string;
  role: string;
  temporaryPassword: string;
}

export interface ApiKey {
  id: string;
  name: string;
  keyPrefix: string;
  scope: string;
  createdBy: string | null;
  createdAt: string;
  lastUsedAt: string | null;
  rotatedAt: string | null;
  revokedAt: string | null;
  revoked: boolean;
}

export interface CreatedApiKey {
  id: string;
  name: string;
  keyPrefix: string;
  scope: string;
  secret: string;
}

export interface WebhookEvent {
  id: string;
  provider: string;
  providerReference: string;
  eventId: string;
  eventType: string;
  signatureValid: boolean;
  processed: boolean;
  payload: string;
  createdAt: string;
}

export interface BandCounts {
  total: number;
  allow: number;
  monitor: number;
  mfa: number;
  hold: number;
  reject: number;
}

export interface PolicyImpact {
  windowDays: number;
  current: BandCounts;
  candidate: BandCounts;
}

export interface ComponentHealth {
  status: string;
  up: boolean;
  latencyMs: number | null;
}

export interface LatencyStat {
  status: string;
  endpoint: string;
  samples: number;
  meanMs: number | null;
  maxMs: number | null;
}

export interface OutboxHealth {
  status: string;
  pending: number;
  oldestPendingAgeSeconds: number | null;
}

export interface WebhookHealth {
  status: string;
  total: number;
  invalidSignature: number;
  unprocessed: number;
  failureRatePct: number;
}

export interface ReconciliationHealth {
  status: string;
  openIssues: number;
  criticalOpen: number;
  oldestOpenAgeSeconds: number | null;
  lastIssueAt: string | null;
}

export interface PaymentsHealth {
  status: string;
  awaitingProviderConfirmation: number;
}

export interface LockHealth {
  status: string;
  waitingLocks: number;
}

export interface CertificationHealth {
  status: string;
  productionConfigs: number;
  certified: number;
  expiringSoon: number;
  uncertified: number;
}

export interface MonitoringSnapshot {
  overallStatus: string;
  banner: string;
  database: ComponentHealth;
  transferLatency: LatencyStat;
  fraudScoringLatency: LatencyStat;
  outbox: OutboxHealth;
  webhooks: WebhookHealth;
  reconciliation: ReconciliationHealth;
  payments: PaymentsHealth;
  dbLockWait: LockHealth;
  certifications: CertificationHealth;
}

export interface ProviderConfigView {
  id: string;
  provider: string;
  environment: string;
  enabled: boolean;
  complianceStatus: string;
  operationalStatus: string;
  emergencyDisabled: boolean;
  allowedCurrencies: string | null;
  allowedDestinationCountries: string | null;
  minimumAmount: number | null;
  maximumAmount: number | null;
  credentialsConfigured: boolean;
  webhookSecretConfigured: boolean;
}

export interface DrillResultView {
  drillId: string;
  drillVersion: string;
  status: string; // PASS | FAIL
  detail: unknown; // { assertions: [...], observations: {...} } — never contains secrets
}

export interface CertificationRun {
  id: string;
  tenantProviderConfigId: string;
  environment: string;
  status: string; // RUNNING | PASSED | FAILED
  catalogueVersion: string;
  evidenceExportId: string | null;
  signedOff: boolean;
  startedAt: string | null;
  completedAt: string | null;
  expiresAt: string | null;
  drills: DrillResultView[];
}

export interface ProductionCanaryView {
  id: string;
  tenantProviderConfigId: string;
  environment: string;
  status: string;
  requestedBy: string;
  approvedBy: string | null;
  approvedAt: string | null;
  startsAt: string;
  expiresAt: string;
  maxTransactionAmount: number;
  maxCumulativeAmount: number;
  maxTransactions: number;
  reservedTransactions: number;
  reservedAmount: number;
  settledTransactions: number;
  failedTransactions: number;
  unknownTransactions: number;
  reversedTransactions: number;
  pauseReason: string | null;
  version: number;
}

export interface ProductionCanaryRequest {
  startsAt: string;
  expiresAt: string;
  maxTransactionAmount: number;
  maxCumulativeAmount: number;
  maxTransactions: number;
  failurePauseThreshold: number;
  unknownPauseThreshold: number;
  reversalPauseThreshold: number;
}

export interface ApiError {
  code: string;
  error: string;
}
