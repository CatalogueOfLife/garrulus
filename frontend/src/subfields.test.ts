import { SUBFIELDS, subfieldLabel, subfieldOptions } from './subfields';

describe('subfields', () => {
  it('namesTheHarvestedSubfields', () => {
    expect(subfieldLabel('1105')).toBe('Ecology, Evolution, Behavior and Systematics');
    expect(subfieldLabel('1109')).toBe('Insect Science');
    expect(subfieldLabel('2303')).toBe('Ecology');
    expect(Object.keys(SUBFIELDS).sort()).toEqual(['1103', '1104', '1105', '1109', '1110', '1911', '2303', '2404', '2405']);
  });

  it('unknownCodesFallBackToTheCode', () => {
    expect(subfieldLabel('9999')).toBe('9999');
  });

  it('optionsKeepAnUnknownCurrentValue', () => {
    const opts = subfieldOptions('9999');
    expect(opts[0]).toEqual({ value: '', label: 'Any' });
    expect(opts).toContainEqual({ value: '1105', label: 'Ecology, Evolution, Behavior and Systematics' });
    expect(opts).toContainEqual({ value: '9999', label: '9999' });
    expect(subfieldOptions('1105').filter((o) => o.value === '1105')).toHaveLength(1);
    expect(subfieldOptions().map((o) => o.label).slice(1)).toEqual([...subfieldOptions().map((o) => o.label).slice(1)].sort());
  });
});
