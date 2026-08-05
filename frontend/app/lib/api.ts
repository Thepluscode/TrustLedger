import type {
  AccountView,
  ApiKey,
  AssessResponse,
  AuditLogView,
  AuthResponse,
  CreatedApiKey,
  MonitoringSnapshot,
  MlModelView,
  ApprovalView,
  PayoutInstrumentView,
  ProviderRecipientView,
  BeneficiaryView,
  DashboardSummary,
  EvidenceExportView,
  ExternalPaymentResponse,
  BeneficiaryProfile,
  CertificationRun,
  DeviceProfile,
  FraudCaseView,
  FraudSignalFrequency,
  FraudSignalDetail,
  FraudPolicy,
  PolicyImpact,
  InvitedUser,
  ProductionCanaryRequest,
  ProductionCanaryView,
  ProviderConfigView,
  ProviderCredentialView,
  RetentionPolicyRequest,
  ReconciliationAuditEntry,
  ReconciliationIssue,
  ReconciliationIssueList,
  SettlementStatement,
  SettlementStatementDetail,
  SettlementIngestResult,
  TeamMember,
  UserProfile,
  WebhookEvent,
  LedgerEntryView,
  LedgerTransactionView,
  MyScope,
  OrgUnit,
  TransferDetail,
  TransferListItem,
  TransferResponse,
} from "./types";

const BASE = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080";
const TOKEN_KEY = "trustledger.token";
const REFRESH_KEY = "trustledger.refresh";
const SESSION_KEY = "trustledger.session";

/** Non-secret session display info (email/role/tenant) for the shell. The JWT stays the only credential. */
export interface SessionInfo {
  email: string;
  role: string;
  tenantId: string;
}

export function getToken(): string | null {
  if (typeof window === "undefined") return null;
  return window.localStorage.getItem(TOKEN_KEY);
}

export function setToken(token: string | null): void {
  if (typeof window === "undefined") return;
  if (token) window.localStorage.setItem(TOKEN_KEY, token);
  else window.localStorage.removeItem(TOKEN_KEY);
}

export function getRefreshToken(): string | null {
  if (typeof window === "undefined") return null;
  return window.localStorage.getItem(REFRESH_KEY);
}

export function setRefreshToken(token: string | null): void {
  if (typeof window === "undefined") return;
  if (token) window.localStorage.setItem(REFRESH_KEY, token);
  else window.localStorage.removeItem(REFRESH_KEY);
}

/** Fired when the session ends unexpectedly, so the shell can route to login. */
export const SESSION_EXPIRED_EVENT = "trustledger:session-expired";

/** Clears every trace of the session locally. Server-side revocation is api.logout(). */
export function clearSession(): void {
  setToken(null);
  setRefreshToken(null);
  setSession(null);
}

export function getSession(): SessionInfo | null {
  if (typeof window === "undefined") return null;
  try {
    const raw = window.localStorage.getItem(SESSION_KEY);
    return raw ? (JSON.parse(raw) as SessionInfo) : null;
  } catch {
    return null;
  }
}

export function setSession(info: SessionInfo | null): void {
  if (typeof window === "undefined") return;
  if (info) window.localStorage.setItem(SESSION_KEY, JSON.stringify(info));
  else window.localStorage.removeItem(SESSION_KEY);
}

/**
 * In-flight refresh, shared by every caller. Refresh tokens are single-use and rotate on the
 * server, so two concurrent refreshes would invalidate each other and log the user out mid-work.
 * One promise, awaited by all, is the only safe shape here.
 */
let refreshInFlight: Promise<string | null> | null = null;

async function refreshAccessToken(): Promise<string | null> {
  const refreshToken = getRefreshToken();
  if (!refreshToken) return null;

  const res = await fetch(`${BASE}/api/v1/auth/refresh`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ refreshToken }),
  });
  if (!res.ok) return null;

  const body = (await res.json()) as AuthResponse;
  if (!body.token) return null;
  setToken(body.token);
  // The server rotated the family: store the new one or the next refresh replays a dead token.
  if (body.refreshToken) setRefreshToken(body.refreshToken);
  setSession({ email: body.email, role: body.role, tenantId: body.tenantId });
  return body.token;
}

function sharedRefresh(): Promise<string | null> {
  if (!refreshInFlight) {
    refreshInFlight = refreshAccessToken()
      .catch(() => null)
      .finally(() => {
        refreshInFlight = null;
      });
  }
  return refreshInFlight;
}

async function send(path: string, options: RequestInit, token: string | null): Promise<Response> {
  const headers: Record<string, string> = {
    "Content-Type": "application/json",
    ...(options.headers as Record<string, string> | undefined),
  };
  if (token) headers["Authorization"] = `Bearer ${token}`;
  return fetch(`${BASE}${path}`, { ...options, headers });
}

async function request<T>(path: string, options: RequestInit = {}): Promise<T> {
  let res = await send(path, options, getToken());

  // A 401 on a short-lived JWT usually means expiry, not revocation — try exactly one refresh.
  // Auth endpoints are excluded: refreshing a failed login would loop.
  if (res.status === 401 && !path.startsWith("/api/v1/auth/")) {
    const refreshed = await sharedRefresh();
    if (refreshed) res = await send(path, options, refreshed);
  }

  if (res.status === 401) {
    clearSession();
    // Clearing credentials isn't enough: without this the user sits on a dead page with no data
    // and no way to tell why. The shell listens and routes to login.
    if (typeof window !== "undefined") window.dispatchEvent(new Event(SESSION_EXPIRED_EVENT));
    throw new Error("Your session has expired — please sign in again.");
  }

  const text = await res.text();
  const body = text ? JSON.parse(text) : null;
  if (!res.ok) {
    throw new Error(body?.error ?? `Request failed (${res.status})`);
  }
  return body as T;
}

export const api = {
  register: (tenantName: string, email: string, password: string) =>
    request<AuthResponse>("/api/v1/auth/register", {
      method: "POST",
      body: JSON.stringify({ tenantName, email, password }),
    }),

  /** Confirms the stored token is still valid and returns the authoritative role/tenant. */
  me: () => request<AuthResponse>("/api/v1/auth/me"),

  /**
   * Revokes the refresh-token family server-side, then clears local state. The short-lived JWT
   * stays valid until it expires — that is why discarding it locally is part of logging out, not
   * an optimisation.
   */
  logout: async (): Promise<void> => {
    const refreshToken = getRefreshToken();
    try {
      if (refreshToken) {
        await fetch(`${BASE}/api/v1/auth/logout`, {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ refreshToken }),
        });
      }
    } finally {
      // A failed revocation must never trap the user in a session they asked to leave.
      clearSession();
    }
  },

  login: (tenantId: string, email: string, password: string) =>
    request<AuthResponse>("/api/v1/auth/login", {
      method: "POST",
      body: JSON.stringify({ tenantId, email, password }),
    }),

  dashboard: () => request<DashboardSummary>("/api/v1/dashboard/summary"),

  listAccounts: () => request<AccountView[]>("/api/v1/accounts"),

  createAccount: (currency: string, openingBalance: string) =>
    request<AccountView>("/api/v1/accounts", {
      method: "POST",
      body: JSON.stringify({ currency, openingBalance }),
    }),

  listBeneficiaries: () => request<BeneficiaryView[]>("/api/v1/beneficiaries"),

  createBeneficiary: (name: string, destinationAccountId: string) =>
    request<BeneficiaryView>("/api/v1/beneficiaries", {
      method: "POST",
      body: JSON.stringify({ name, destinationAccountId }),
    }),

  /**
   * Payout instruments are where an external payout actually lands. A beneficiary without one
   * cannot be paid, which is why this is wired before the routing niceties.
   */
  listPayoutInstruments: (beneficiaryId: string) =>
    request<PayoutInstrumentView[]>(`/api/v1/beneficiaries/${beneficiaryId}/payout-instruments`),

  createPayoutInstrument: (
    beneficiaryId: string,
    body: {
      instrumentType: string;
      country: string;
      currency: string;
      accountName: string;
      bankCode: string;
      maskedIdentifier: string;
      externalReference: string;
    },
  ) =>
    request<PayoutInstrumentView>(`/api/v1/beneficiaries/${beneficiaryId}/payout-instruments`, {
      method: "POST",
      body: JSON.stringify(body),
    }),

  updatePayoutInstrumentStatus: (beneficiaryId: string, instrumentId: string, status: string) =>
    request<PayoutInstrumentView>(
      `/api/v1/beneficiaries/${beneficiaryId}/payout-instruments/${instrumentId}/status`,
      { method: "PATCH", body: JSON.stringify({ status }) },
    ),

  listProviderRecipients: (beneficiaryId: string, instrumentId: string) =>
    request<ProviderRecipientView[]>(
      `/api/v1/beneficiaries/${beneficiaryId}/payout-instruments/${instrumentId}/provider-recipients`,
    ),

  /** Maps our instrument to the provider's own recipient token, so a payout can be addressed. */
  registerProviderRecipient: (
    beneficiaryId: string,
    instrumentId: string,
    tenantProviderConfigId: string,
    providerRecipientCode: string,
  ) =>
    request<ProviderRecipientView>(
      `/api/v1/beneficiaries/${beneficiaryId}/payout-instruments/${instrumentId}/provider-recipients`,
      { method: "POST", body: JSON.stringify({ tenantProviderConfigId, providerRecipientCode }) },
    ),

  /** Maker-checker: the requester cannot approve their own request — enforced server-side. */
  listApprovals: () => request<ApprovalView[]>("/api/v1/approvals"),

  createApproval: (actionType: string, resourceType: string, resourceId: string, reason: string) =>
    request<ApprovalView>("/api/v1/approvals", {
      method: "POST",
      body: JSON.stringify({ actionType, resourceType, resourceId, reason }),
    }),

  approveApproval: (id: string) => request<ApprovalView>(`/api/v1/approvals/${id}/approve`, { method: "POST" }),

  rejectApproval: (id: string) => request<ApprovalView>(`/api/v1/approvals/${id}/reject`, { method: "POST" }),

  accountLedger: (accountId: string) =>
    request<LedgerEntryView[]>(`/api/v1/accounts/${accountId}/ledger`),

  ledgerTransaction: (id: string) =>
    request<LedgerTransactionView>(`/api/v1/ledger/transactions/${id}`),

  listAuditLogs: () => request<AuditLogView[]>("/api/v1/audit-logs"),

  listUsers: () => request<TeamMember[]>("/api/v1/users"),
  inviteUser: (email: string, role: string) =>
    request<InvitedUser>("/api/v1/users/invite", { method: "POST", body: JSON.stringify({ email, role }) }),
  changeUserRole: (id: string, role: string) =>
    request<TeamMember>(`/api/v1/users/${id}/role`, { method: "PATCH", body: JSON.stringify({ role }) }),

  listOrgUnits: () => request<OrgUnit[]>("/api/v1/org-units"),
  myScope: () => request<MyScope>("/api/v1/org-units/my-scope"),
  createOrgUnit: (name: string, type: string, parentUnitId: string | null) =>
    request<OrgUnit>("/api/v1/org-units", {
      method: "POST",
      body: JSON.stringify({ name, type, parentUnitId }),
    }),
  assignOrgUnitMember: (unitId: string, userId: string) =>
    request<void>(`/api/v1/org-units/${unitId}/members`, {
      method: "POST",
      body: JSON.stringify({ userId }),
    }),

  listApiKeys: () => request<ApiKey[]>("/api/v1/developer/api-keys"),
  createApiKey: (name: string, scope: string) =>
    request<CreatedApiKey>("/api/v1/developer/api-keys", { method: "POST", body: JSON.stringify({ name, scope }) }),
  rotateApiKey: (id: string) =>
    request<CreatedApiKey>(`/api/v1/developer/api-keys/${id}/rotate`, { method: "POST" }),
  revokeApiKey: (id: string) =>
    request<ApiKey>(`/api/v1/developer/api-keys/${id}/revoke`, { method: "POST" }),

  getMonitoring: () => request<MonitoringSnapshot>("/api/v1/monitoring"),

  listWebhookEvents: () => request<WebhookEvent[]>("/api/v1/payment-rails/webhooks"),

  listSettlementStatements: () => request<SettlementStatement[]>("/api/v1/tenant/reconciliation/statements"),
  getSettlementStatement: (id: string) =>
    request<SettlementStatementDetail>(`/api/v1/tenant/reconciliation/statements/${id}`),
  ingestSettlementStatement: (body: Record<string, unknown>) =>
    request<SettlementIngestResult>("/api/v1/tenant/reconciliation/statements", {
      method: "POST",
      body: JSON.stringify(body),
    }),
  ingestSettlementStatementCsv: (body: Record<string, unknown>) =>
    request<SettlementIngestResult>("/api/v1/tenant/reconciliation/statements/csv", {
      method: "POST",
      body: JSON.stringify(body),
    }),

  listReconciliationIssues: (status?: string, severity?: string) => {
    const q = new URLSearchParams();
    if (status) q.set("status", status);
    if (severity) q.set("severity", severity);
    const qs = q.toString();
    return request<ReconciliationIssueList>(`/api/v1/reconciliation/issues${qs ? `?${qs}` : ""}`);
  },
  getReconciliationIssue: (id: string) => request<ReconciliationIssue>(`/api/v1/reconciliation/issues/${id}`),
  reconciliationIssueAudit: (id: string) =>
    request<ReconciliationAuditEntry[]>(`/api/v1/reconciliation/issues/${id}/audit`),
  resolveReconciliationIssue: (id: string, outcome: string, note: string) =>
    request<ReconciliationIssue>(`/api/v1/reconciliation/issues/${id}/resolve`, {
      method: "POST",
      body: JSON.stringify({ outcome, note }),
    }),

  deviceProfiles: () => request<DeviceProfile[]>("/api/v1/fraud/risk-profiles/devices"),
  beneficiaryProfiles: () => request<BeneficiaryProfile[]>("/api/v1/fraud/risk-profiles/beneficiaries"),
  userProfiles: () => request<UserProfile[]>("/api/v1/fraud/risk-profiles/users"),

  createTransfer: (idempotencyKey: string, body: Record<string, unknown>) =>
    request<TransferResponse>("/api/v1/transfers", {
      method: "POST",
      headers: { "Idempotency-Key": idempotencyKey },
      body: JSON.stringify(body),
    }),

  createExternalTransfer: (idempotencyKey: string, body: Record<string, unknown>) =>
    request<ExternalPaymentResponse>("/api/v1/transfers/external", {
      method: "POST",
      headers: { "Idempotency-Key": idempotencyKey },
      body: JSON.stringify(body),
    }),

  finalizePaystackOtp: (transactionId: string, otp: string) =>
    request<ExternalPaymentResponse>(`/api/v1/transfers/external/${transactionId}/paystack-otp`, {
      method: "POST",
      body: JSON.stringify({ otp }),
    }),

  listTransfers: () => request<TransferListItem[]>("/api/v1/transfers"),
  getTransfer: (id: string) => request<TransferDetail>(`/api/v1/transfers/${id}`),

  verifyMfa: (transactionId: string, code: string) =>
    request<TransferResponse>(`/api/v1/transfers/${transactionId}/mfa/verify`, {
      method: "POST",
      body: JSON.stringify({ code }),
    }),

  assessRisk: (deviceId: string, beneficiaryAccountId: string, amount: string) =>
    request<AssessResponse>("/api/v1/fraud/assess", {
      method: "POST",
      body: JSON.stringify({ deviceId, beneficiaryAccountId, amount }),
    }),

  listFraudCases: () => request<FraudCaseView[]>("/api/v1/fraud/cases"),
  fraudSignalSummary: () => request<FraudSignalFrequency[]>("/api/v1/fraud/signals/summary"),
  fraudCaseSignals: (caseId: string) =>
    request<FraudSignalDetail[]>(`/api/v1/fraud/cases/${caseId}/signals`),

  approveCase: (caseId: string) =>
    request<TransferResponse>(`/api/v1/fraud/cases/${caseId}/approve`, { method: "POST" }),

  rejectCase: (caseId: string) =>
    request<TransferResponse>(`/api/v1/fraud/cases/${caseId}/reject`, { method: "POST" }),

  exportFraudCaseEvidence: (caseId: string) =>
    request<EvidenceExportView>(`/api/v1/evidence/fraud-cases/${caseId}`, { method: "POST" }),

  listEvidence: () => request<EvidenceExportView[]>("/api/v1/evidence/exports"),

  /** Export a ledger transaction as evidence — the ledger half of the fraud-case pack. */
  exportLedgerEvidence: (ledgerTxId: string) =>
    request<EvidenceExportView>(`/api/v1/evidence/ledger/${ledgerTxId}`, { method: "POST" }),

  /**
   * Legal hold blocks deletion until released. Sent as a query param because the backend reads it
   * with @RequestParam, not from a body.
   */
  setEvidenceLegalHold: (id: string, on: boolean) =>
    request<void>(`/api/v1/evidence/exports/${id}/legal-hold?on=${on}`, { method: "POST" }),

  /** Refused by the backend while a legal hold is in force — the guard is server-side, not here. */
  deleteEvidenceExport: (id: string) =>
    request<void>(`/api/v1/evidence/exports/${id}`, { method: "DELETE" }),

  upsertRetentionPolicy: (policy: RetentionPolicyRequest) =>
    request<void>("/api/v1/evidence/retention-policies", {
      method: "POST",
      body: JSON.stringify(policy),
    }),

  downloadEvidence: async (id: string): Promise<void> => {
    const res = await fetch(`${BASE}/api/v1/evidence/exports/${id}/download`, {
      headers: { Authorization: `Bearer ${getToken() ?? ""}` },
    });
    const blob = await res.blob();
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = `evidence-${id}.json`;
    a.click();
    URL.revokeObjectURL(url);
  },

  getUsage: (metric: string) =>
    request<{ currentMonth: number }>(`/api/v1/tenant/usage?metric=${encodeURIComponent(metric)}`),
  getTenantQuota: () => request<Record<string, number>>("/api/v1/tenant/quota"),
  changePlan: (plan: string) =>
    request<{ plan: string }>("/api/v1/tenant/plan", { method: "PUT", body: JSON.stringify({ plan }) }),
  getBillingEvents: () => request<string[]>("/api/v1/tenant/billing/events"),
  getProductionReadiness: () =>
    request<{ productionExecutionEnabled: boolean; activeCanaryRequired: boolean; policy: string }>(
      "/api/v1/tenant/production-readiness",
    ),
  /** Enable/disable and the emergency stop — the two controls that gate real money movement. */
  updateProviderControls: (configId: string, enabled: boolean, emergencyDisabled: boolean) =>
    request<ProviderConfigView>(`/api/v1/tenant/provider-configs/${configId}/controls`, {
      method: "PATCH",
      body: JSON.stringify({ enabled, emergencyDisabled }),
    }),

  listProviderCredentials: (configId: string) =>
    request<ProviderCredentialView[]>(`/api/v1/tenant/provider-configs/${configId}/credentials`),

  /** secretRef is a REFERENCE (vault://…), never the secret itself — the backend rejects raw values. */
  createProviderCredential: (configId: string, purpose: string, secretRef: string) =>
    request<ProviderCredentialView>(`/api/v1/tenant/provider-configs/${configId}/credentials`, {
      method: "POST",
      body: JSON.stringify({ purpose, secretRef }),
    }),

  /**
   * expectedActiveCredentialId is a compare-and-set guard: if another operator rotated in the
   * meantime, the backend refuses rather than silently overwriting their activation. graceSeconds
   * keeps the previous credential valid for inbound signature verification during the cutover.
   */
  activateProviderCredential: (
    configId: string,
    credentialId: string,
    expectedActiveCredentialId: string | null,
    graceSeconds: number,
  ) =>
    request<ProviderCredentialView>(
      `/api/v1/tenant/provider-configs/${configId}/credentials/${credentialId}/activate`,
      { method: "POST", body: JSON.stringify({ expectedActiveCredentialId, graceSeconds }) },
    ),

  revokeProviderCredential: (configId: string, credentialId: string) =>
    request<ProviderCredentialView>(
      `/api/v1/tenant/provider-configs/${configId}/credentials/${credentialId}/revoke`,
      { method: "POST" },
    ),

  listProviderConfigs: () => request<ProviderConfigView[]>("/api/v1/tenant/provider-configs"),

  listCertifications: () => request<CertificationRun[]>("/api/v1/tenant/certifications"),
  getCertification: (id: string) => request<CertificationRun>(`/api/v1/tenant/certifications/${id}`),
  runCertification: (tenantProviderConfigId: string) =>
    request<CertificationRun>("/api/v1/tenant/certifications", {
      method: "POST",
      body: JSON.stringify({ tenantProviderConfigId }),
    }),
  signOffCertification: (id: string, note: string) =>
    request<CertificationRun>(`/api/v1/tenant/certifications/${id}/sign-off`, {
      method: "POST",
      body: JSON.stringify({ note }),
    }),
  listProductionCanaries: (configId: string) =>
    request<ProductionCanaryView[]>(`/api/v1/tenant/provider-configs/${configId}/production-canaries`),
  requestProductionCanary: (configId: string, body: ProductionCanaryRequest) =>
    request<ProductionCanaryView>(`/api/v1/tenant/provider-configs/${configId}/production-canaries`, {
      method: "POST",
      body: JSON.stringify(body),
    }),
  approveProductionCanary: (configId: string, planId: string) =>
    request<ProductionCanaryView>(
      `/api/v1/tenant/provider-configs/${configId}/production-canaries/${planId}/approve`,
      { method: "POST" },
    ),
  pauseProductionCanary: (configId: string, planId: string, reason: string) =>
    request<ProductionCanaryView>(
      `/api/v1/tenant/provider-configs/${configId}/production-canaries/${planId}/pause`,
      { method: "POST", body: JSON.stringify({ reason }) },
    ),
  resumeProductionCanary: (configId: string, planId: string) =>
    request<ProductionCanaryView>(
      `/api/v1/tenant/provider-configs/${configId}/production-canaries/${planId}/resume`,
      { method: "POST" },
    ),

  getFraudPolicy: () => request<FraudPolicy>("/api/v1/tenant/fraud-policy"),
  updateFraudPolicy: (body: FraudPolicy) =>
    request<FraudPolicy>("/api/v1/tenant/fraud-policy", { method: "PUT", body: JSON.stringify(body) }),
  previewFraudPolicyImpact: (body: FraudPolicy) =>
    request<PolicyImpact>("/api/v1/tenant/fraud-policy/impact", { method: "POST", body: JSON.stringify(body) }),

  listMlModels: () =>
    request<MlModelView[]>("/api/v2/ml/models"),

  /**
   * Advances a model through CANDIDATE -> SHADOW -> ANALYST_ASSIST. The backend refuses to promote
   * into a money-moving mode: ML informs decisions, it never makes them (v2.8 rule).
   */
  promoteMlModel: (modelId: string) =>
    request<MlModelView>(`/api/v2/ml/models/${modelId}/promote`, { method: "POST" }),

  rollbackMlModel: (modelId: string) =>
    request<MlModelView>(`/api/v2/ml/models/${modelId}/rollback`, { method: "POST" }),

  /** Returns the alerts raised by this snapshot (e.g. MODEL_LATENCY_HIGH), not a success flag. */
  recordMlMonitoringSnapshot: (modelVersion: string, metrics: Record<string, number>) =>
    request<{ alerts: string[] }>("/api/v2/ml/monitoring", {
      method: "POST",
      body: JSON.stringify({ modelVersion, metrics }),
    }),

  /** Raw metric JSON strings, newest-first, as stored by the monitoring service. */
  mlMonitoringHistory: (modelVersion: string) =>
    request<string[]>(`/api/v2/ml/monitoring/${encodeURIComponent(modelVersion)}`),

  /**
   * An analyst's verdict on a case, which is what the model is later retrained against. Sending it
   * is how a human correction re-enters the loop rather than dying in a comment field.
   */
  submitFraudFeedback: (caseId: string, transactionId: string, label: string, confidence: string, reason: string) =>
    request<{ id: string; fraudCaseId: string; label: string }>(`/api/v2/fraud/cases/${caseId}/feedback`, {
      method: "POST",
      body: JSON.stringify({ transactionId, label, confidence, reason }),
    }),

  listFraudFeedback: () =>
    request<{ id: string; fraudCaseId: string; label: string }[]>("/api/v2/fraud/feedback"),
  getMlScores: (transactionId: string) =>
    request<
      {
        transactionId: string;
        modelVersion: string;
        featureSetVersion: string;
        fraudProbability: string;
        riskBand: string;
        explanationJson: string;
        shadowMode: boolean;
        latencyMs: number;
      }[]
    >(`/api/v2/ml/fraud-scores/${transactionId}`),
};
