import { useQuery } from '@tanstack/react-query';
import { getMe } from '../api';

/** data is the user, null when signed out, undefined while loading. */
export const useMe = () => useQuery({ queryKey: ['me'], queryFn: getMe, staleTime: 60_000 });

const NEXT_KEY = 'garrulus.next';

/** Only same-origin relative paths; anything else is dropped. */
export function safeNext(next: string | null | undefined): string | null {
  return next && next.startsWith('/') && !next.startsWith('//') && !next.includes('\\') ? next : null;
}
export function rememberNext(next: string | null) {
  try {
    const n = safeNext(next);
    if (n) sessionStorage.setItem(NEXT_KEY, n);
    else sessionStorage.removeItem(NEXT_KEY);
  } catch { /* storage unavailable */ }
}
export function takeNext(): string {
  try {
    const n = safeNext(sessionStorage.getItem(NEXT_KEY));
    sessionStorage.removeItem(NEXT_KEY);
    return n ?? '/';
  } catch {
    return '/';
  }
}
