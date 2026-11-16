// Typed client of the public Garrulus API; the types follow web/.../static/api/openapi.yaml.

export type JournalType = 'TAXONOMIC' | 'GENERAL';
export type Tier = 'CORE' | 'BROAD' | 'CANDIDATE' | 'EXCLUDED';
export type OaModel = 'DIAMOND' | 'GOLD' | 'HYBRID' | 'CLOSED';
export type FacetName = 'code' | 'taxgroup' | 'journalType' | 'year' | 'source' | 'hasActs';

export interface Error400 {
  status: number;
  error: string;
}

export class ApiError extends Error {
  readonly status: number;
  constructor(status: number, message: string) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
  }
}

export interface TaxonRef { id?: string | null; name?: string | null; rank?: string | null }
export interface SourceRef { id: number; title: string }
export interface NameRef {
  id: number;
  name: string;
  placementMethod: 'MATCH' | 'GENUS' | 'VERBATIM_CONTEXT' | 'NONE';
  usageId?: string | null;
}
export interface WorkSummary {
  id: number;
  doi?: string | null;
  title?: string | null;
  year?: number | null;
  publicationDate?: string | null;
  source?: SourceRef | null;
  authors: string[];
  authorCount: number;
  textLevel?: 'FULLTEXT' | 'ABSTRACT_ONLY' | 'NO_TEXT' | null;
  hasActs: boolean;
  mainNames: NameRef[];
}
export interface FacetCount { value: string; count: number }
export interface WorkPage {
  total: number;
  limit: number;
  offset: number;
  results: WorkSummary[];
  facets: Partial<Record<FacetName, FacetCount[]>>;
}
export interface Author { name: string; orcid?: string | null }
export interface NameDetail {
  id: number;
  name: string;
  kind: string;
  rank?: string | null;
  placementMethod: string;
  usageId?: string | null;
  usageName?: string | null;
  usageRank?: string | null;
  usageStatus?: string | null;
  code?: string | null;
  taxgroup?: string | null;
  salience: number;
  mainSubject: boolean;
  onlyReference: boolean;
  mentionCount: number;
  actTypes: string[];
  bestSnippet: string;
  classification: TaxonRef[];
}
export interface MentionSpan {
  id: number;
  nameId: number;
  startCp: number;
  endCp: number;
  verbatim: string;
  actType?: string | null;
}
export interface TextBlock {
  id: string;
  type: string;
  level: number;
  sectionPath: string[];
  text: string;
  mentions: MentionSpan[];
}
/** One ranked name of a verbatim classification; rank is upper case (ORDER, FAMILY, ...). */
export interface RankedName { rank: string; name: string }
export interface Context {
  localId: string;
  verbatim: string;
  /** Highest first. */
  ranks: RankedName[];
  scope: string;
  blockId: string;
  untilBlockId?: string | null;
}
export interface WorkDetail {
  id: number;
  doi?: string | null;
  title?: string | null;
  year?: number | null;
  publicationDate?: string | null;
  source?: SourceRef | null;
  authors: Author[];
  volume?: string | null;
  issue?: string | null;
  pages?: string | null;
  language?: string | null;
  type?: string | null;
  oaStatus?: string | null;
  bestOaUrl?: string | null;
  abstract?: string | null;
  licence?: string | null;
  retained: boolean;
  textLevel?: string | null;
  hasActs: boolean;
  codes: string[];
  taxgroups: string[];
  names: NameDetail[];
  contexts: Context[];
  text?: TextBlock[] | null;
}
export interface NameHit { id: number; name: string; kind: string; rank?: string | null; works: number }
export interface NameMatch {
  usageId?: string | null;
  usageName?: string | null;
  rank?: string | null;
  status?: string | null;
  acceptedId?: string | null;
  classification: TaxonRef[];
}
export interface NameFull {
  id: number;
  name: string;
  kind: string;
  rank?: string | null;
  match?: NameMatch | null;
  timeline: { year: number; works: number }[];
  firstAppearance?: { workId: number; title?: string | null; publicationDate?: string | null } | null;
}
export interface TaxonStats {
  taxonId: string;
  total: number;
  mainSubjectTotal: number;
  years: { year: number; works: number; mainSubjectWorks: number }[];
}
export interface SourceItem {
  id: number;
  title: string;
  publisher?: string | null;
  tier: 'CORE' | 'BROAD';
  journalType?: JournalType | null;
  primarySubfield?: string | null;
  subfields: string[];
  taxgroups: string[];
  codes: string[];
  oaModel?: OaModel | null;
  country?: string | null;
  nameDensity?: number | null;
  actRate?: number | null;
  worksPerYear?: number | null;
}
export interface SourcePage { total: number; limit: number; offset: number; results: SourceItem[] }
export interface SourceDetail extends SourceItem {
  issnL?: string | null;
  taxgroupShares?: Record<string, number> | null;
  recentWorks: WorkSummary[];
}
export interface TaxGroup { name: string; parents: string[]; codes: string[]; icon: string }
export interface Xr { key: number; alias: string }

/** The search filters; the names are those of the URL query string and of the API. */
export interface WorkFilter {
  taxon?: string;
  name?: string;
  code?: string;
  taxgroup?: string;
  subfield?: string;
  journalType?: string;
  tier?: string;
  oa?: string;
  country?: string;
  source?: string;
  yearFrom?: string;
  yearTo?: string;
  hasActs?: string;
  actType?: string;
  mainSubjectOnly?: string;
  q?: string;
  limit?: string;
  offset?: string;
}

export const FILTER_KEYS: (keyof WorkFilter)[] = [
  'taxon', 'name', 'code', 'taxgroup', 'subfield', 'journalType', 'tier', 'oa', 'country', 'source',
  'yearFrom', 'yearTo', 'hasActs', 'actType', 'mainSubjectOnly', 'q', 'limit', 'offset',
];

export function filterFromParams(params: URLSearchParams): WorkFilter {
  const f: WorkFilter = {};
  for (const k of FILTER_KEYS) {
    const v = params.get(k);
    if (v) f[k] = v;
  }
  return f;
}

export function filterToParams(filter: WorkFilter): URLSearchParams {
  const p = new URLSearchParams();
  for (const k of FILTER_KEYS) {
    const v = filter[k];
    if (v !== undefined && v !== '') p.set(k, v);
  }
  return p;
}

async function getJson<T>(url: string): Promise<T> {
  const res = await fetch(url, { headers: { Accept: 'application/json' } });
  if (!res.ok) {
    let msg = `Request failed (${res.status})`;
    try {
      const body = (await res.json()) as Partial<Error400>;
      if (body.error) msg = body.error;
    } catch {
      /* not JSON */
    }
    throw new ApiError(res.status, msg);
  }
  return (await res.json()) as T;
}

export function searchWorks(filter: WorkFilter, facets: FacetName[] = []): Promise<WorkPage> {
  const p = filterToParams(filter);
  for (const f of facets) p.append('facet', f);
  return getJson<WorkPage>(`/api/works?${p}`);
}
export const getWork = (id: number | string) => getJson<WorkDetail>(`/api/works/${encodeURIComponent(id)}`);
export const getName = (id: number | string) => getJson<NameFull>(`/api/names/${encodeURIComponent(id)}`);
export const searchNames = (q: string, limit = 10) =>
  getJson<NameHit[]>(`/api/names?${new URLSearchParams({ q, limit: String(limit) })}`);
export const getTaxonStats = (id: string) => getJson<TaxonStats>(`/api/taxa/${encodeURIComponent(id)}/stats`);
export const getSource = (id: number | string) => getJson<SourceDetail>(`/api/sources/${encodeURIComponent(id)}`);
export const listSources = (params: { q?: string; tier?: string; journalType?: string; limit?: number; offset?: number } = {}) => {
  const p = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) if (v !== undefined && v !== '') p.set(k, String(v));
  return getJson<SourcePage>(`/api/sources?${p}`);
};
export const getXr = () => getJson<Xr>('/api/xr');
export const getTaxGroups = () => getJson<TaxGroup[]>('/api/vocab/taxgroup');

export const CLB_BASE = 'https://api.checklistbank.org';

export interface TaxonSuggestion {
  match: string;
  context?: string;
  usageId: string;
  rank?: string;
  status?: string;
  suggestion: string;
}

/** The browser calls the CLB suggest API directly, in the pinned XR dataset. */
export function suggestTaxa(xrKey: number, q: string): Promise<TaxonSuggestion[]> {
  return getJson<TaxonSuggestion[]>(`${CLB_BASE}/dataset/${xrKey}/nameusage/suggest?${new URLSearchParams({ q })}`);
}

export interface ClbTaxon {
  id: string;
  label?: string;
  status?: string;
  parentId?: string | null;
  name: { scientificName: string; rank?: string };
}
export interface ClbClassificationItem { id: string; name: string; rank?: string; label?: string }

/** The taxon header, straight from CLB in the pinned XR dataset. */
export const getClbTaxon = (xrKey: number, id: string) =>
  getJson<ClbTaxon>(`${CLB_BASE}/dataset/${xrKey}/taxon/${encodeURIComponent(id)}`);
/** The classification of a taxon, root first. */
export const getClbClassification = (xrKey: number, id: string) =>
  getJson<ClbClassificationItem[]>(`${CLB_BASE}/dataset/${xrKey}/taxon/${encodeURIComponent(id)}/classification`);

// ---- accounts ----

export type DigestFrequency = 'DAILY' | 'WEEKLY' | 'OFF';
export interface Me {
  id: number;
  email?: string | null;
  emailVerified: boolean;
  orcid?: string | null;
  displayName?: string | null;
  timeZone?: string | null;
  digestFrequency: DigestFrequency;
  digestWeekday: number;
  digestHour: number;
}
export interface MePatch {
  displayName?: string | null;
  timeZone?: string;
  digestFrequency?: DigestFrequency;
  digestWeekday?: number;
  digestHour?: number;
}
export interface SubscriptionFilters {
  code?: string; taxgroup?: string; subfield?: string; journalType?: string; tier?: string; oa?: string; country?: string;
}
export interface Subscription {
  id: number;
  type: 'TAXON' | 'SOURCE' | 'NAME' | 'TAXGROUP';
  target: string;
  label: string;
  mainSubjectOnly: boolean;
  newNamesOnly: boolean;
  includeAbstractOnly: boolean;
  includeReferences: boolean;
  filters: SubscriptionFilters;
  sinceYear?: number | null;
  paused: boolean;
  feedUrl: string;
  created: string;
}
export interface Hit {
  id: number;
  kind: 'ARTICLE' | 'NEW_NAMES';
  names: unknown[];
  subscriptionId: number;
  subscriptionLabel?: string | null;
  created: string;
  work?: WorkSummary | null;
}
export type EmailOutcome = 'SET' | 'MERGED' | 'REFUSED_OTHER_ORCID';

function xsrfToken(): string | null {
  const m = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]*)/);
  return m ? decodeURIComponent(m[1]) : null;
}

/** A non-GET request: sends the XSRF header from the cookie and the session cookie; JSON in and out (form when body is a URLSearchParams). */
export async function send<T = unknown>(method: string, url: string, body?: unknown): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json' };
  const xsrf = xsrfToken();
  if (xsrf) headers['X-XSRF-TOKEN'] = xsrf;
  let payload: BodyInit | undefined;
  if (body instanceof URLSearchParams) {
    headers['Content-Type'] = 'application/x-www-form-urlencoded';
    payload = body.toString();
  } else if (body !== undefined) {
    headers['Content-Type'] = 'application/json';
    payload = JSON.stringify(body);
  }
  const res = await fetch(url, { method, headers, body: payload, credentials: 'same-origin' });
  if (!res.ok) throw new ApiError(res.status, `Request failed (${res.status})`);
  const text = await res.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

export const getAuthConfig = () => getJson<{ orcid: boolean }>('/api/auth/config');
/** The signed-in user, or null when signed out. */
export async function getMe(): Promise<Me | null> {
  try {
    const res = await fetch('/api/me', { headers: { Accept: 'application/json' }, credentials: 'same-origin' });
    if (res.status === 401) return null;
    if (!res.ok) throw new ApiError(res.status, `Request failed (${res.status})`);
    return (await res.json()) as Me;
  } catch (e) {
    if (e instanceof ApiError) throw e;
    throw new ApiError(0, 'Network error');
  }
}
export const requestSignInLink = (email: string) => send('POST', '/api/auth/link', { email });
export const signInWithToken = (token: string) => send<{ userId: number }>('POST', '/login/ott', new URLSearchParams({ token }));
export const signOut = () => send('POST', '/api/auth/logout');
export const patchMe = (p: MePatch) => send<Me>('PATCH', '/api/me', p);
export const requestEmailChange = (email: string) => send('POST', '/api/me/email', { email });
export const verifyEmail = (token: string) => send<{ outcome: EmailOutcome }>('POST', '/api/auth/verify-email', { token });
export const deleteMe = () => send('DELETE', '/api/me');
export const unsubscribe = (t: string) => send('POST', `/api/unsubscribe?${new URLSearchParams({ t })}`);

/** Downloads the export as a file. */
export async function downloadExport(): Promise<void> {
  const res = await fetch('/api/me/export', { credentials: 'same-origin' });
  if (!res.ok) throw new ApiError(res.status, `Request failed (${res.status})`);
  const url = URL.createObjectURL(await res.blob());
  const a = document.createElement('a');
  a.href = url;
  a.download = 'garrulus-export.json';
  a.click();
  URL.revokeObjectURL(url);
}
