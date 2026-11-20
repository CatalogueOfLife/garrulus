import { disciplineLabel, disciplineOptions } from './disciplines';
import { disciplines } from './test/server';

describe('disciplines', () => {
  it('labelsFromVocabulary', () => {
    expect(disciplineLabel('1109', disciplines)).toBe('Insect Science');
    expect(disciplineLabel('2303', disciplines)).toBe('Ecology');
    const opts = disciplineOptions(disciplines);
    expect(opts[0]).toEqual({ value: '', label: 'Any' });
    // vocabulary order, retired codes not offered
    expect(opts.slice(1).map((o) => o.value)).toEqual(['1103', '1105', '1109', '1110', '2303']);
  });

  it('retiredCodeShowsReplacement', () => {
    expect(disciplineLabel('9000', disciplines)).toBe('Insect Science');
    const opts = disciplineOptions(disciplines, '9000');
    expect(opts.filter((o) => o.label === 'Insect Science')).toHaveLength(1);
    expect(opts.some((o) => o.value === '9000')).toBe(false);
  });

  it('unknownCodeShownAsCode', () => {
    expect(disciplineLabel('9999', disciplines)).toBe('9999');
    expect(disciplineLabel('9999')).toBe('9999');
    expect(disciplineOptions(disciplines, '9999')).toContainEqual({ value: '9999', label: '9999' });
    expect(disciplineOptions(disciplines, '1105').filter((o) => o.value === '1105')).toHaveLength(1);
  });
});
