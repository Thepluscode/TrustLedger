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

export interface ApiError {
  code: string;
  error: string;
}
