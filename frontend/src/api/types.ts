// Types of the fixed contract (openapi.yaml). Field names and nullability follow the contract.

export type Role = 'INGEST' | 'OPERATOR' | 'SUPERVISOR';
export type AlertStatus = 'OPEN' | 'CLAIMED' | 'RESOLVED';

export interface LoginResponse {
  accessToken: string;
  tokenType: string;
  expiresInSec: number;
  user: { username: string; role: Role };
}

export interface AssetPosition {
  assetId: string;
  kind?: string | null;
  reportId: string;
  observedAt: string;
  lat: number;
  lon: number;
  speedKn?: number | null;
  headingDeg?: number | null;
  source?: string | null;
}

export type SourceType = 'SATELLITE' | 'GROUND' | 'MOBILE' | 'UNKNOWN';

export interface TrackPoint {
  reportId: string;
  observedAt: string;
  lat: number;
  lon: number;
  source?: string | null;
  /** Added by CR-1 (v1.1.0); absent when talking to a v1.0.0 instance. */
  sourceType?: SourceType;
}

export interface Page<T> {
  items: T[];
  truncated: boolean;
}

export interface Alert {
  alertId: string;
  assetId: string;
  zoneId: string;
  status: AlertStatus;
  openedAt: string;
  triggerReportId: string;
  triggerObservedAt?: string;
  claimedBy?: string | null;
  claimedAt?: string | null;
  resolvedBy?: string | null;
  resolvedAt?: string | null;
  resolutionNote?: string | null;
}

export interface AlertPage {
  items: Alert[];
  nextCursor: string | null;
}

export interface AuditEntry {
  at: string;
  actor: string;
  action: 'OPENED' | 'CLAIMED' | 'RELEASED' | 'RESOLVED';
  fromStatus?: AlertStatus | null;
  toStatus: AlertStatus;
}

export interface Stats {
  received: number;
  accepted: number;
  duplicates: number;
  rejected: number;
  assets: number;
  alerts: { open: number; claimed: number; resolved: number };
}

export interface Problem {
  type: string;
  title: string;
  status: number;
  code: string;
  detail?: string;
}

// ---- additions outside the fixed contract (console support endpoints) ----

/** GeoJSON geometry as delivered by the upstream zone file. Coordinates are [lon, lat]. */
export type ZoneGeometry =
  | { type: 'Polygon'; coordinates: number[][][] }
  | { type: 'MultiPolygon'; coordinates: number[][][][] };

export interface Zone {
  zoneId: string;
  name: string;
  zoneClass: 'RESTRICTED' | 'ADVISORY';
  crossesAntimeridian: boolean;
  geometry: ZoneGeometry;
}

export interface ZoneOccupancy {
  zoneId: string;
  name: string;
  zoneClass: 'RESTRICTED' | 'ADVISORY';
  count: number;
  truncated: boolean;
  assets: { assetId: string; kind: string | null; observedAt: string }[];
}

/** Live stream position tuple: [assetId, lat, lon, headingDeg, kind, observedAt, reportId, speedKn]. */
export type PositionTuple = [string, number, number, number | null, string | null, string, string, number | null];

export interface AlertChange {
  alert: Alert;
  version: number;
}
