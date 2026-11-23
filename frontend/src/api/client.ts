import type {
  Alert, AlertPage, AlertStatus, AssetPosition, AuditEntry, LoginResponse, Page, Problem, Stats, TrackPoint, Zone,
  ZoneOccupancy,
} from './types';

/** A refused request, carrying the contract's problem code. */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    readonly detail: string | undefined,
  ) {
    super(detail ?? code);
  }
}

type Unauthorized = () => void;

/**
 * Thin typed client. Same-origin only: the console is served by the load balancer that fronts the
 * API, so no request ever goes to another host.
 */
export class Api {
  private token: string | null = null;
  private onUnauthorized: Unauthorized = () => {};

  setToken(token: string | null): void {
    this.token = token;
  }

  getToken(): string | null {
    return this.token;
  }

  setUnauthorizedHandler(handler: Unauthorized): void {
    this.onUnauthorized = handler;
  }

  private async request<T>(method: string, path: string, body?: unknown, signal?: AbortSignal): Promise<T> {
    const headers: Record<string, string> = { Accept: 'application/json' };
    if (this.token) headers.Authorization = `Bearer ${this.token}`;
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    const init: RequestInit = { method, headers };
    if (body !== undefined) init.body = JSON.stringify(body);
    if (signal) init.signal = signal;
    const res = await fetch(path, init);
    if (!res.ok) {
      let problem: Partial<Problem> = {};
      try {
        problem = (await res.json()) as Partial<Problem>;
      } catch {
        // not a problem body (for example the load balancer answering 502)
      }
      if (res.status === 401 && this.token) this.onUnauthorized();
      throw new ApiError(res.status, problem.code ?? `HTTP_${res.status}`, problem.detail);
    }
    return (await res.json()) as T;
  }

  login(username: string, password: string): Promise<LoginResponse> {
    return this.request('POST', '/api/v1/auth/login', { username, password });
  }

  stats(): Promise<Stats> {
    return this.request('GET', '/api/v1/stats');
  }

  assets(bbox: [number, number, number, number], limit: number, signal?: AbortSignal): Promise<Page<AssetPosition>> {
    const b = bbox.map((v) => v.toFixed(5)).join(',');
    return this.request('GET', `/api/v1/assets?bbox=${b}&limit=${limit}`, undefined, signal);
  }

  asset(assetId: string): Promise<AssetPosition> {
    return this.request('GET', `/api/v1/assets/${encodeURIComponent(assetId)}`);
  }

  track(assetId: string, from: string | null, limit: number): Promise<Page<TrackPoint>> {
    const q = new URLSearchParams({ limit: String(limit) });
    if (from) q.set('from', from);
    return this.request('GET', `/api/v1/assets/${encodeURIComponent(assetId)}/track?${q.toString()}`);
  }

  alerts(params: { status?: AlertStatus; assetId?: string; zoneId?: string; limit?: number; cursor?: string | null }): Promise<AlertPage> {
    const q = new URLSearchParams();
    if (params.status) q.set('status', params.status);
    if (params.assetId) q.set('assetId', params.assetId);
    if (params.zoneId) q.set('zoneId', params.zoneId);
    q.set('limit', String(params.limit ?? 100));
    if (params.cursor) q.set('cursor', params.cursor);
    return this.request('GET', `/api/v1/alerts?${q.toString()}`);
  }

  alert(alertId: string): Promise<Alert> {
    return this.request('GET', `/api/v1/alerts/${encodeURIComponent(alertId)}`);
  }

  audit(alertId: string): Promise<{ items: AuditEntry[] }> {
    return this.request('GET', `/api/v1/alerts/${encodeURIComponent(alertId)}/audit`);
  }

  claim(alertId: string): Promise<Alert> {
    return this.request('POST', `/api/v1/alerts/${encodeURIComponent(alertId)}/claim`);
  }

  release(alertId: string): Promise<Alert> {
    return this.request('POST', `/api/v1/alerts/${encodeURIComponent(alertId)}/release`);
  }

  resolve(alertId: string, note: string): Promise<Alert> {
    return this.request('POST', `/api/v1/alerts/${encodeURIComponent(alertId)}/resolve`, { note });
  }

  zones(): Promise<{ items: Zone[] }> {
    return this.request('GET', '/api/v1/zones');
  }

  occupancy(): Promise<{ items: ZoneOccupancy[] }> {
    return this.request('GET', '/api/v1/zones/occupancy');
  }
}

export const api = new Api();
