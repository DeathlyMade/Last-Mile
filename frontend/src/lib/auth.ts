// Simple token-based auth helpers (no complex refresh logic)
// Token is opaque UUID issued by backend and stored in Redis for ~1 hour.

export interface SessionUser {
  id: string;
  name: string;
  email: string;
  phone: string;
  role: 'rider' | 'driver';
}

const USER_KEY = 'user';
const TOKEN_KEY = 'auth_token';
const TS_KEY = 'auth_ts';
const MAX_AGE_MS = 60 * 60 * 1000; // 1 hour, mirrors backend TTL

export function getToken(): string | null {
  return localStorage.getItem(TOKEN_KEY);
}

export function getUser(): SessionUser | null {
  const raw = localStorage.getItem(USER_KEY);
  if (!raw) return null;
  try { return JSON.parse(raw); } catch { return null; }
}

export function setSession(user: SessionUser, token?: string) {
  if (token) localStorage.setItem(TOKEN_KEY, token);
  localStorage.setItem(USER_KEY, JSON.stringify(user));
  localStorage.setItem(TS_KEY, Date.now().toString());
}

export function clearSession() {
  localStorage.removeItem(TOKEN_KEY);
  localStorage.removeItem(USER_KEY);
  localStorage.removeItem(TS_KEY);
}

export function isAuthenticated(): boolean {
  const token = getToken();
  if (!token) return false;
  const tsRaw = localStorage.getItem(TS_KEY);
  if (!tsRaw) return true; // treat as valid if timestamp missing
  const age = Date.now() - parseInt(tsRaw, 10);
  return age < MAX_AGE_MS;
}

// Lightweight name resolution used both by register and login flows.
export function resolveName(profileName?: string, fallbackFormName?: string): string {
  if (profileName && profileName.trim()) return profileName.trim();
  if (fallbackFormName && fallbackFormName.trim()) return fallbackFormName.trim();
  const existing = getUser()?.name;
  if (existing && existing.trim()) return existing.trim();
  return 'User';
}

// Imperative auth guard: ensures a valid session or redirects.
// Returns the current SessionUser if authenticated; otherwise redirects to login.
export function requireAuth(redirectPath: string = '/login'): SessionUser {
  if (!isAuthenticated()) {
    window.location.href = redirectPath;
    throw new Error('Redirecting to login');
  }
  const user = getUser();
  if (!user) {
    window.location.href = redirectPath;
    throw new Error('Redirecting to login');
  }
  return user;
}
