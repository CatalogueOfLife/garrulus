import { useQuery } from '@tanstack/react-query';
import { getMe } from '../api';

/** data is the user, null when signed out, undefined while loading. */
export const useMe = () => useQuery({ queryKey: ['me'], queryFn: getMe, staleTime: 60_000 });

const NEXT_KEY = 'garrulus.next';
const NEXT_TTL_MS = 60 * 60 * 1000;

/** Only same-origin relative paths; anything else is dropped. */
export function safeNext(next: string | null | undefined): string | null {
  return next && next.startsWith('/') && !next.startsWith('//') && !next.includes('\\') ? next : null;
}
/** Kept in localStorage (the mailed link opens in a new tab) for one hour. */
export function rememberNext(next: string | null) {
  try {
    const n = safeNext(next);
    if (n) localStorage.setItem(NEXT_KEY, JSON.stringify({ path: n, ts: Date.now() }));
    else localStorage.removeItem(NEXT_KEY);
  } catch { /* storage unavailable */ }
}
/** The remembered path if it is fresh and safe, else null; always clears it. */
export function takeNext(): string | null {
  try {
    const raw = localStorage.getItem(NEXT_KEY);
    localStorage.removeItem(NEXT_KEY);
    if (!raw) return null;
    const v = JSON.parse(raw) as { path?: string; ts?: number };
    if (typeof v.ts !== 'number' || Date.now() - v.ts > NEXT_TTL_MS) return null;
    return safeNext(v.path);
  } catch {
    return null;
  }
}
