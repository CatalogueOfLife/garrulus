import { sentence, words } from './sentence';

describe('sentence', () => {
  it('sets an upper-case API value in sentence case, underscores as spaces', () => {
    expect(sentence('TAXONOMIC')).toBe('Taxonomic');
    expect(sentence('SECTION_BOTANY')).toBe('Section botany');
    expect(sentence(undefined)).toBeUndefined();
    expect(sentence(null)).toBeNull();
  });
});

describe('words', () => {
  it('sets an API value in lower-case words, for a rank or status inside a line', () => {
    expect(words('SECTION_BOTANY')).toBe('section botany');
    expect(words('section_botany')).toBe('section botany');
    expect(words('provisionally accepted')).toBe('provisionally accepted');
    expect(words('')).toBe('');
    expect(words(undefined)).toBeUndefined();
  });
});
