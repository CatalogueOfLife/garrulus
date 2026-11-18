/**
 * An upper-case API value in sentence case ("TAXONOMIC" → "Taxonomic", "SECTION_BOTANY" → "Section botany"): the design
 * has no all-caps labels.
 */
export const sentence = <T extends string | null | undefined>(v: T): T =>
  (v ? v.charAt(0) + v.slice(1).toLowerCase().replace(/_/g, ' ') : v) as T;

/** An API value in lower-case words ("SECTION_BOTANY" → "section botany"), for a rank or status inside a line. */
export const words = <T extends string | null | undefined>(v: T): T => (v ? v.toLowerCase().replace(/_/g, ' ') : v) as T;
