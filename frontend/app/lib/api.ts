import type {
  AccountView,
  ApiKey,
  AssessResponse,
  AuditLogView,
  AuthResponse,
  CreatedApiKey,
  MonitoringSnapshot,
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
  ReconBundle,
  ReconCase,
  ReconFeed,
  ReconFeedCreated,
  ReconCaseView,
  ReconIssueActivity,
  ReconRunView,
  ReconRun,
  ReconSourceRow,
  ReconImportManifest,
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
const SESSION_KEY = "trustledger.session";

/** Non-secret session display info (email/role/tenant) for the shell. The JWT stays the only credential. */
export interface SessionInfo {
  email: string;
  role: string;
  tenantId: string;
  /** Absent in sessions stored before it was added; features that need it degrade until the next sign-in. */
  userId?: string;
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

async function request<T>(path: string, options: RequestInit = {}): Promise<T> {
  // A multipart body must set its own Content-Type (it carries the boundary).
  const headers: Record<string, string> = {
    ...(options.body instanceof FormData ? {} : { "Content-Type": "application/json" }),
    ...(options.headers as Record<string, string> | undefined),
  };
  const token = getToken();
  if (token) headers["Authorization"] = `Bearer ${token}`;

  const res = await fetch(`${BASE}${path}`, { ...options, headers });
  if (res.status === 401) {
    setToken(null);
    throw new Error("Unauthorized");
  }
  const text = await res.text();
  const body = text ? JSON.parse(text) : null;
  if (!res.ok) {
    throw new Error(body?.error ?? body?.manifest?.failureReason ?? `Request failed (${res.status})`);
  }
  return body as T;
}

export const api = {
  register: (tenantName: string, email: string, password: string) =>
    request<AuthResponse>("/api/v1/auth/register", {
      method: "POST",
      body: JSON.stringify({ tenantName, email, password }),
    }),

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

  listReconciliationIssues: (status?: string, severity?: string, more: Record<string, string> = {}) => {
    const q = new URLSearchParams();
    if (status) q.set("status", status);
    if (severity) q.set("severity", severity);
    for (const [k, v] of Object.entries(more)) if (v) q.set(k, v);
    const qs = q.toString();
    return request<ReconciliationIssueList>(`/api/v1/reconciliation/issues${qs ? `?${qs}` : ""}`);
  },
  getReconciliationIssue: (id: string) => request<ReconciliationIssue>(`/api/v1/reconciliation/issues/${id}`),
  reconciliationIssueAudit: (id: string) =>
    request<ReconciliationAuditEntry[]>(`/api/v1/reconciliation/issues/${id}/audit`),
  resolveReconciliationIssue: (id: string, outcome: string, note: string, evidenceRef?: string | null, expectedVersion?: number) =>
    request<ReconciliationIssue>(`/api/v1/reconciliation/issues/${id}/resolve`, {
      method: "POST",
      body: JSON.stringify({ outcome, note, evidenceRef: evidenceRef || null, expectedVersion }),
    }),
  transitionReconciliationIssue: (id: string, to: string, expectedVersion: number) =>
    request<ReconciliationIssue>(`/api/v1/reconciliation/issues/${id}/transition`, {
      method: "POST",
      body: JSON.stringify({ to, expectedVersion }),
    }),
  commentOnReconciliationIssue: (id: string, body: string) =>
    request<ReconIssueActivity>(`/api/v1/reconciliation/issues/${id}/comments`, { method: "POST", body: JSON.stringify({ body }) }),
  addReconciliationIssueEvidence: (id: string, file: File, note: string) => {
    const form = new FormData();
    form.set("file", file);
    if (note) form.set("note", note);
    return request<ReconIssueActivity>(`/api/v1/reconciliation/issues/${id}/evidence`, { method: "POST", body: form });
  },
  reconciliationAssignees: () => request<{ id: string; email: string; role: string }[]>("/api/v1/reconciliation/issues/assignees"),
  /** Needs the bearer token, so it cannot be a plain link. Always served as a download. */
  downloadReconciliationIssueEvidence: async (id: string, seq: number): Promise<Blob> => {
    const res = await fetch(`${BASE}/api/v1/reconciliation/issues/${id}/evidence/${seq}`, {
      headers: { Authorization: `Bearer ${getToken() ?? ""}` },
    });
    if (!res.ok) throw new Error(`Evidence download failed (${res.status})`);
    return res.blob();
  },
  reconciliationIssueActivity: (id: string) =>
    request<ReconIssueActivity[]>(`/api/v1/reconciliation/issues/${id}/activity`),

  listReconCases: () => request<ReconCase[]>("/api/v1/reconciliation/cases"),
  createReconCase: (body: { caseRef: string; title: string; periodStart: string; periodEnd: string; settlementSlaDays: number }) =>
    request<{ reconciliationCase: ReconCase; replayed: boolean }>("/api/v1/reconciliation/cases", { method: "POST", body: JSON.stringify(body) }),
  getReconCase: (caseId: string) => request<ReconCaseView>(`/api/v1/reconciliation/cases/${caseId}`),
  importReconFile: (caseId: string, sourceType: string, sourceIdentity: string, profile: string, file: File) => {
    const form = new FormData();
    form.set("sourceType", sourceType);
    form.set("sourceIdentity", sourceIdentity);
    form.set("profile", profile);
    form.set("file", file);
    return request<{ manifest: ReconImportManifest; replayed: boolean }>(`/api/v1/reconciliation/cases/${caseId}/imports`, { method: "POST", body: form });
  },
  reconImportRows: (caseId: string, importId: string, status: string) =>
    request<ReconSourceRow[]>(`/api/v1/reconciliation/cases/${caseId}/imports/${importId}/rows?status=${status}`),
  acknowledgeReconRejections: (caseId: string, importId: string) =>
    request<ReconImportManifest>(`/api/v1/reconciliation/cases/${caseId}/imports/${importId}/acknowledge-rejections`, { method: "POST" }),
  discardReconImport: (caseId: string, importId: string) =>
    request<ReconImportManifest>(`/api/v1/reconciliation/cases/${caseId}/imports/${importId}/discard`, { method: "POST" }),
  runReconCase: (caseId: string) => request<ReconRunView>(`/api/v1/reconciliation/cases/${caseId}/runs`, { method: "POST" }),
  listReconRuns: (caseId: string) => request<ReconRun[]>(`/api/v1/reconciliation/cases/${caseId}/runs`),
  getReconRun: (caseId: string, runId: string) => request<ReconRunView>(`/api/v1/reconciliation/cases/${caseId}/runs/${runId}`),
  listReconFeeds: (caseId: string) => request<ReconFeed[]>(`/api/v1/reconciliation/cases/${caseId}/feeds`),
  createReconFeed: (caseId: string, providerIdentity: string) =>
    request<ReconFeedCreated>(`/api/v1/reconciliation/cases/${caseId}/feeds`, { method: "POST", body: JSON.stringify({ providerIdentity }) }),
  revokeReconFeed: (caseId: string, feedId: string) =>
    request<ReconFeed>(`/api/v1/reconciliation/cases/${caseId}/feeds/${feedId}/revoke`, { method: "POST" }),
  closeReconCase: (caseId: string) => request<ReconCase>(`/api/v1/reconciliation/cases/${caseId}/close`, { method: "POST" }),
  exportReconBundle: (caseId: string) => request<ReconBundle>(`/api/v1/reconciliation/cases/${caseId}/bundle`, { method: "POST" }),
  /** The download needs the bearer token, so it cannot be a plain link. */
  downloadEvidenceExport: async (exportId: string): Promise<Blob> => {
    const res = await fetch(`${BASE}/api/v1/evidence/exports/${exportId}/download`, {
      headers: { Authorization: `Bearer ${getToken() ?? ""}` },
    });
    if (!res.ok) throw new Error(`Download failed (${res.status})`);
    return res.blob();
  },
  assignReconciliationIssue: (id: string, userId: string | null, expectedVersion?: number) =>
    request<ReconciliationIssue>(`/api/v1/reconciliation/issues/${id}/assign`, {
      method: "POST",
      body: JSON.stringify({ userId, expectedVersion }),
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
    request<{ id: string; modelName: string; version: string; status: string; deploymentMode: string }[]>("/api/v2/ml/models"),
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
