import { isEnglish, languageName } from './languages';

describe('languages', () => {
  it('zhIsChinese', () => {
    expect(languageName('zh')).toBe('Chinese');
    expect(languageName('de')).toBe('German');
  });
  it('fallsBackToTheCode', () => {
    expect(languageName('not a code!')).toBe('not a code!');
  });
  it('englishIsRecognised', () => {
    expect(isEnglish('en')).toBe(true);
    expect(isEnglish('en-GB')).toBe(true);
    expect(isEnglish(null)).toBe(true);
    expect(isEnglish('zh')).toBe(false);
  });
});
