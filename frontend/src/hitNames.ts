import type { HitName } from './api';

/** Names shown per hit; the rest is "+N more" (as in digests and private feeds). */
export const MAX_HIT_NAMES = 10;

const ACT_LABELS: Record<string, string> = {
  SP_NOV: 'sp. nov.', SSP_NOV: 'ssp. nov.', VAR_NOV: 'var. nov.', GEN_NOV: 'gen. nov.', SUBGEN_NOV: 'subgen. nov.',
  FAM_NOV: 'fam. nov.', HIGHER_NOV: 'higher taxon nov.', COMB_NOV: 'comb. nov.', STAT_NOV: 'stat. nov.',
  STAT_REV: 'stat. rev.', SYN_NOV: 'syn. nov.', NOM_NOV: 'nom. nov.', TYPE_DESIGNATION: 'type designation',
};

/** The label of an act type, e.g. "sp. nov."; an unknown one in lower case without underscores. */
export const actLabel = (type: string) => ACT_LABELS[type] ?? type.toLowerCase().replace(/_/g, ' ');

/** Names with acts first, then main subjects, then the rest (each in snapshot order), at most MAX_HIT_NAMES. */
export function shownHitNames(names: HitName[]): { names: { name: string; acts: string[] }[]; more: number } {
  const rank = (n: HitName) => (n.actTypes?.length ? 0 : n.mainSubject ? 1 : 2);
  const sorted = names
    .filter((n) => n.name && n.name.trim())
    .map((n, i) => ({ n, i }))
    .sort((a, b) => rank(a.n) - rank(b.n) || a.i - b.i)
    .map(({ n }) => ({ name: n.name, acts: (n.actTypes ?? []).map(actLabel) }));
  return { names: sorted.slice(0, MAX_HIT_NAMES), more: Math.max(0, sorted.length - MAX_HIT_NAMES) };
}
