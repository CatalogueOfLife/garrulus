import { setupServer } from 'msw/node';

export const server = setupServer();

import { http, HttpResponse } from 'msw';
import type { TaxGroup, WorkPage, WorkSummary } from '../api';

export const work = (id: number, title: string): WorkSummary => ({
  id, title, year: 2026, authors: ['A. Author'], authorCount: 1, hasActs: false, mainNames: [],
  source: { id: 7, title: 'Zootaxa' },
});

export const emptyPage: WorkPage = { total: 0, limit: 20, offset: 0, results: [], facets: {} };

export const taxGroups: TaxGroup[] = [
  { name: 'Animals', parents: [], codes: ['ZOOLOGICAL'], icon: 'https://icons.example/animals.svg' },
  { name: 'Insects', parents: ['Animals'], codes: ['ZOOLOGICAL'], icon: 'https://icons.example/insects.svg' },
];

export const xrHandler = http.get('/api/xr', () => HttpResponse.json({ key: 316691, alias: 'COL26.10 XR' }));
export const taxGroupHandler = http.get('/api/vocab/taxgroup', () => HttpResponse.json(taxGroups));

export const authConfigHandler = (orcid: boolean) =>
  http.get('/api/auth/config', () => HttpResponse.json({ orcid }));
export const signedOut = http.get('/api/me', () => new HttpResponse(null, { status: 401 }));
export const meBody = {
  id: 1, email: 'a@example.org', emailVerified: true, orcid: null, displayName: 'A',
  timeZone: null, digestFrequency: 'DAILY', digestWeekday: 1, digestHour: 7,
};
