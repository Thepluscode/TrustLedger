import type {
  AccountView,
  AssessResponse,
  AuthResponse,
  BeneficiaryView,
  DashboardSummary,
  EvidenceExportView,
  ExternalPaymentResponse,
  FraudCaseView,
  TransferResponse,
} from "./types";

const BASE = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080";
const TOKEN_KEY = "trustledger.token";

export function getToken(): string | null {
  if (typeof window === "undefined") return null;
  return window.localStorage.getItem(TOKEN_KEY);
}

export function setToken(token: string | null): void {
  if (typeof window === "undefined") return;
  if (token) window.localStorage.setItem(TOKEN_KEY, token);
  else window.localStorage.removeItem(TOKEN_KEY);
}

async function request<T>(path: string, options: RequestInit = {}): Promise<T> {
  const headers: Record<string, string> = {
    "Content-Type": "application/json",
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

  assessRisk: (deviceId: string, beneficiaryAccountId: string, amount: string) =>
    request<AssessResponse>("/api/v1/fraud/assess", {
      method: "POST",
      body: JSON.stringify({ deviceId, beneficiaryAccountId, amount }),
    }),

  listFraudCases: () => request<FraudCaseView[]>("/api/v1/fraud/cases"),

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
  listProviderConfigs: () =>
    request<{ provider: string; environment: string; enabled: boolean }[]>("/api/v1/tenant/provider-configs"),

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
