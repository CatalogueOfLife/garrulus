export type NameInfo = { rank?: string | null; usageRank?: string | null; kind?: string | null };

/**
 * The ranks above genus (roman), as the name-parser 6 `Rank` enum orders them before GENUS, plus `division` and
 * `suprageneric name` of older data. Every other known rank is genus or below and italic (R26): the list of ranks
 * below genus is long and grows (series botany, forma specialis, pathovar, cultivar ...), the list above it is stable.
 */
const ROMAN_RANKS = new Set([
  'superdomain', 'domain', 'subdomain', 'infradomain', 'empire', 'realm', 'subrealm',
  'superkingdom', 'kingdom', 'subkingdom', 'infrakingdom',
  'superphylum', 'phylum', 'subphylum', 'infraphylum', 'parvphylum', 'microphylum', 'nanophylum', 'claudius',
  'gigaclass', 'megaclass', 'superclass', 'class', 'subclass', 'infraclass', 'subterclass', 'parvclass',
  'superdivision', 'division', 'division zoology', 'subdivision', 'infradivision',
  'superlegion', 'legion', 'sublegion', 'infralegion',
  'megacohort', 'supercohort', 'cohort', 'subcohort', 'infracohort',
  'gigaorder', 'magnorder', 'grandorder', 'mirorder', 'superorder', 'order', 'nanorder', 'hypoorder', 'minorder',
  'suborder', 'infraorder', 'parvorder',
  'supersection zoology', 'section zoology', 'subsection zoology',
  'superseries zoology', 'series zoology', 'subseries zoology', 'falanx',
  'gigafamily', 'megafamily', 'grandfamily', 'superfamily', 'epifamily', 'family', 'subfamily', 'infrafamily',
  'supertribe', 'tribe', 'subtribe', 'infratribe', 'suprageneric name', 'supergenus',
]);
const UNKNOWN_RANKS = new Set(['', 'unranked', 'other']);

/** Same pattern as the Java SciNames.italicByShape: binomials and trinomials are italic. */
const SHAPE = /^[A-Z][a-z-]+(?: \([A-Z][a-z-]+\))? [a-z-]+(?: (?:(?:subsp|ssp|var|subvar|f|forma|morph|ab)\. )?[a-z-]+)?$/;

const norm = (r?: string | null) => (r ?? '').trim().toLowerCase().replace(/_/g, ' ');

/** Above genus roman, every other known rank italic; unknown ranks by usage rank, then by name shape; OTU codes roman. */
export function isItalicName(name: string, info: NameInfo = {}): boolean {
  if (info.kind?.toUpperCase() === 'OTU') return false;
  for (const r of [norm(info.rank), norm(info.usageRank)]) {
    if (!UNKNOWN_RANKS.has(r)) return !ROMAN_RANKS.has(r);
  }
  return SHAPE.test(name.trim());
}

/**
 * Rank markers, qualifiers and the hybrid sign: set roman between the italic parts of a name (R27). The same list is
 * in the Java SciNames. "sp." counts only after "f." (forma specialis), "x" only between two words.
 */
const MARKERS = new Set([
  'subsp.', 'ssp.', 'var.', 'subvar.', 'f.', 'forma', 'pv.', 'bv.', 'sv.', 'morph', 'ab.', 'cv.', 'cf.', 'aff.', '×',
]);

/** Whether `words[i]` is a marker inside a name of these words. */
export function isNameMarker(words: string[], i: number): boolean {
  const w = words[i];
  if (MARKERS.has(w)) return true;
  if (w === 'sp.') return words[i - 1] === 'f.';
  return w === 'x' && i > 0 && i < words.length - 1;
}

export type NameRun = { text: string; italic: boolean };

/** The runs of a name: for an italic name, its markers and hybrid signs (and the spaces next to them) roman. */
export function nameRuns(text: string, italic: boolean): NameRun[] {
  if (!italic) return [{ text, italic: false }];
  // words and the whitespace between them; a hybrid sign written onto its epithet ("×piperita") is a word of its own
  const parts: { text: string; space: boolean }[] = [];
  for (const t of text.split(/(\s+)/)) {
    if (!t) continue;
    if (/^\s+$/.test(t)) parts.push({ text: t, space: true });
    else if (t.length > 1 && t.startsWith('×')) parts.push({ text: '×', space: false }, { text: t.slice(1), space: false });
    else parts.push({ text: t, space: false });
  }
  const words = parts.filter((p) => !p.space).map((p) => p.text);
  let w = 0;
  const roman = parts.map((p) => (p.space ? false : isNameMarker(words, w++)));
  // a space is italic only between two italic words
  const styled = parts.map((p, i) => ({
    text: p.text,
    italic: p.space ? i > 0 && i < parts.length - 1 && !roman[i - 1] && !roman[i + 1] : !roman[i],
  }));
  const out: NameRun[] = [];
  for (const s of styled) {
    const last = out[out.length - 1];
    if (last && last.italic === s.italic) last.text += s.text;
    else out.push({ ...s });
  }
  return out;
}
