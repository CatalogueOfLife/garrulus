/** An upper-case API value in sentence case ("TAXONOMIC" → "Taxonomic"): the design has no all-caps labels. */
export const sentence = (v?: string | null) => (v ? v.charAt(0) + v.slice(1).toLowerCase() : v);
