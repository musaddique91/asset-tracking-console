import type { Role } from './api/types';

export interface Session {
  token: string;
  username: string;
  role: Role;
  expiresAt: number;
}

const KEY = 'zw.session';

/** Kept in sessionStorage so a reload keeps the operator signed in; it ends with the browser tab. */
export function loadSession(): Session | null {
  try {
    const raw = sessionStorage.getItem(KEY);
    if (!raw) return null;
    const s = JSON.parse(raw) as Session;
    return s.expiresAt > Date.now() ? s : null;
  } catch {
    return null;
  }
}

export function saveSession(s: Session | null): void {
  if (s) sessionStorage.setItem(KEY, JSON.stringify(s));
  else sessionStorage.removeItem(KEY);
}
