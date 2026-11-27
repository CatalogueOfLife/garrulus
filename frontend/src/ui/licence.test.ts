import { licenceLabel } from './licence';

describe('licenceLabel', () => {
  it('labelsStoredCodesAndRawForms', () => {
    expect(licenceLabel('cc-by')).toBe('CC BY');
    expect(licenceLabel('cc-by-nc-nd')).toBe('CC BY-NC-ND');
    expect(licenceLabel('CC-BY-4.0')).toBe('CC BY 4.0');
    expect(licenceLabel('cc0')).toBe('CC0');
    expect(licenceLabel('public-domain')).toBe('Public domain');
    expect(licenceLabel('publisher-specific-oa')).toBe("Publisher's open licence");
    expect(licenceLabel('Some licence')).toBe('Some licence');
  });
});
