import { setupServer } from 'msw/node';

import { http, HttpResponse } from 'msw';
import type { TaxGroup, WorkPage, WorkSummary } from '../api';

export const work = (id: number, title: string): WorkSummary => ({
  id, title, year: 2026, authors: ['A. Author'], authorCount: 1, hasActs: false, mainNames: [], titleMentions: [],
  source: { id: 7, title: 'Zootaxa' },
});

export const emptyPage: WorkPage = { total: 0, limit: 20, offset: 0, results: [], facets: {} };

export const taxGroups: TaxGroup[] = [
  { name: 'Animals', parents: [], codes: ['ZOOLOGICAL'], icon: 'https://icons.example/animals.svg' },
  { name: 'Insects', parents: ['Animals'], codes: ['ZOOLOGICAL'], icon: 'https://icons.example/insects.svg' },
];

export const xrHandler = http.get('/api/xr', () => HttpResponse.json({ key: 316691, alias: 'COL26.10 XR' }));
export const taxGroupHandler = http.get('/api/vocab/taxgroup', () => HttpResponse.json(taxGroups));

export const sourcesHandler = (total = 312) =>
  http.get('/api/sources', () => HttpResponse.json({ total, limit: 1, offset: 0, results: [] }));

export const authConfigHandler = (orcid: boolean) =>
  http.get('/api/auth/config', () => HttpResponse.json({ orcid }));
export const signedOut = http.get('/api/me', () => new HttpResponse(null, { status: 401 }));
// like the backend: a new account is on UTC; created long ago, so no time zone is proposed
export const meBody = {
  id: 1, email: 'a@example.org', emailVerified: '2026-01-02T03:04:05Z', orcid: null, displayName: 'A',
  timeZone: 'UTC', digestFrequency: 'DAILY', digestWeekday: 1, digestHour: 7, created: '2026-01-01T00:00:00Z',
};

// defaults: signed out and the taxgroup list, which pages with a Follow button ask for
export const server = setupServer(signedOut, taxGroupHandler);
