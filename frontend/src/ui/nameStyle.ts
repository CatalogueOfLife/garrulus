export type NameInfo = { rank?: string | null; usageRank?: string | null; kind?: string | null };

const ITALIC_RANKS = new Set([
  'genus', 'subgenus', 'infrageneric name', 'section', 'section botany', 'subsection', 'subsection botany',
  'series', 'subseries', 'species aggregate', 'species', 'infraspecific name', 'subspecies',
  'infrasubspecific name', 'variety', 'subvariety', 'form', 'subform', 'natio', 'proles', 'morph', 'aberration',
]);
const UNKNOWN_RANKS = new Set(['', 'unranked', 'other']);

/** Same pattern as the Java SciNames.italicByShape: binomials and trinomials are italic. */
const SHAPE = /^[A-Z][a-z-]+(?: \([A-Z][a-z-]+\))? [a-z-]+(?: (?:(?:subsp|ssp|var|subvar|f|forma|morph|ab)\. )?[a-z-]+)?$/;

const norm = (r?: string | null) => (r ?? '').trim().toLowerCase().replace(/_/g, ' ');

/** Genus and below italic, family and above roman; unknown ranks by usage rank, then by name shape; OTU codes roman. */
export function isItalicName(name: string, info: NameInfo = {}): boolean {
  if (info.kind?.toUpperCase() === 'OTU') return false;
  for (const r of [norm(info.rank), norm(info.usageRank)]) {
    if (!UNKNOWN_RANKS.has(r)) return ITALIC_RANKS.has(r);
  }
  return SHAPE.test(name.trim());
}
