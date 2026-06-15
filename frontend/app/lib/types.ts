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

export interface ApiError {
  code: string;
  error: string;
}
