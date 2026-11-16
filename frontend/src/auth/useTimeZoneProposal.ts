import { useEffect } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { patchMe, type Me } from '../api';
import { useMe } from './useMe';

/** Accounts created this recently are new enough to get the browser's zone. */
const NEW_ACCOUNT_MS = 10 * 60 * 1000;
const flagKey = (id: number) => `garrulus.tzProposed.${id}`;

const browserZone = () => {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC';
  } catch {
    return 'UTC';
  }
};

function proposed(id: number): boolean {
  try {
    return localStorage.getItem(flagKey(id)) !== null;
  } catch {
    return true; // without storage, never risk proposing again and again
  }
}

/** The zone to propose to this account, or null. */
export function zoneToPropose(u: Me, now = Date.now()): string | null {
  const zone = browserZone();
  const created = Date.parse(u.created);
  if (u.timeZone !== 'UTC' || zone === 'UTC' || Number.isNaN(created) || now - created > NEW_ACCOUNT_MS) return null;
  return proposed(u.id) ? null : zone;
}

/**
 * New accounts start on UTC (the server cannot know better). On the first signed-in load of an account created in the
 * last 10 minutes and still on UTC, the browser's zone is set once; a flag per user id keeps it from being repeated.
 */
export function useTimeZoneProposal() {
  const me = useMe();
  const client = useQueryClient();
  const u = me.data;
  useEffect(() => {
    if (!u) return;
    const zone = zoneToPropose(u);
    if (!zone) return;
    try {
      localStorage.setItem(flagKey(u.id), zone);
    } catch { /* storage unavailable */ }
    patchMe({ timeZone: zone }).then((m) => client.setQueryData(['me'], m)).catch(() => { /* keep UTC */ });
  }, [u, client]);
}
