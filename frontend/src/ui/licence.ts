/**
 * A work's licence as a short label. The indexer stores Creative Commons codes (`cc-by`, `cc-by-nc-nd`, `cc0`,
 * `public-domain`) and passes OpenAlex's own values through (`other-oa`, `publisher-specific-oa`, ...); a raw form
 * such as `CC-BY-4.0` is read too. Anything else is shown as it is.
 */
const OTHER: Record<string, string> = {
  'cc0': 'CC0',
  'public-domain': 'Public domain',
  'pd': 'Public domain',
  'other-oa': 'Other open licence',
  'publisher-specific-oa': "Publisher's open licence",
  'implied-oa': 'Open access (no licence stated)',
};

export function licenceLabel(licence: string): string {
  const v = licence.trim().toLowerCase();
  if (OTHER[v]) return OTHER[v];
  const cc = /^cc[\s_-]*(by(?:[\s_-]+(?:nc|nd|sa))*)(?:[\s_-]+(\d(?:\.\d)?))?/.exec(v);
  if (cc) return `CC ${cc[1].replace(/[\s_-]+/g, '-').toUpperCase()}${cc[2] ? ` ${cc[2]}` : ''}`;
  return licence.trim();
}
