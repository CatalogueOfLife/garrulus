/**
 * Names of the OpenAlex subfields Garrulus harvests (indexer SourceSeeder.SUBFIELDS). Sources and filters store the
 * bare OpenAlex code; anything not listed here is shown as its code.
 */
export const SUBFIELDS: Record<string, string> = {
  '1103': 'Animal Science and Zoology',
  '1104': 'Aquatic Science',
  '1105': 'Ecology, Evolution, Behavior and Systematics',
  '1109': 'Insect Science',
  '1110': 'Plant Science',
  '1911': 'Paleontology',
  '2303': 'Ecology',
  '2404': 'Microbiology',
  '2405': 'Parasitology',
};

export function subfieldLabel(code: string): string {
  return SUBFIELDS[code] ?? code;
}

/** Select options: "Any", the known subfields by name and, if it is not one of them, the current value. */
export function subfieldOptions(current?: string): { value: string; label: string }[] {
  const known = Object.entries(SUBFIELDS)
    .map(([value, label]) => ({ value, label }))
    .sort((a, b) => a.label.localeCompare(b.label));
  const extra = current && !(current in SUBFIELDS) ? [{ value: current, label: current }] : [];
  return [{ value: '', label: 'Any' }, ...known, ...extra];
}
