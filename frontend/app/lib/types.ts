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

export interface ApiError {
  code: string;
  error: string;
}
