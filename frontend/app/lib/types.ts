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
}

export interface ReconciliationListSummary {
  total: number;
  open: number;
  criticalOpen: number;
  resolved: number;
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
