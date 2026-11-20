import type { ReactNode } from 'react';
import { Alert, Loader } from '@mantine/core';
import { ApiError } from '../../api';
import { NOT_ADMIN } from './AdminLayout';

/** Loading, error (403 reads as "not an admin") or the content. */
export function QueryState({ query, children }: {
  query: { isPending: boolean; isError: boolean; error: unknown };
  children: ReactNode;
}) {
  if (query.isError) {
    const e = query.error;
    return <Alert color="red">{e instanceof ApiError && e.status === 403 ? NOT_ADMIN : (e as Error).message}</Alert>;
  }
  if (query.isPending) return <Loader size="sm" />;
  return <>{children}</>;
}

export const errMessage = (e: unknown) =>
  e instanceof ApiError && e.status === 403 ? NOT_ADMIN : e instanceof ApiError && e.status === 409 ? 'Already exists.'
    : e instanceof ApiError && e.status === 400 ? 'The server refused this input.' : (e as Error).message;

/** A link only for http(s) URLs; the URL is always shown as text next to it. */
export function SafeUrl({ value }: { value: unknown }) {
  const s = String(value ?? '');
  let ok = false;
  try { const u = new URL(s); ok = u.protocol === 'http:' || u.protocol === 'https:'; } catch { /* not a URL */ }
  return ok ? <><span>{s}</span> (<a href={s} rel="noopener noreferrer nofollow" target="_blank">open</a>)</> : <span>{s}</span>;
}
