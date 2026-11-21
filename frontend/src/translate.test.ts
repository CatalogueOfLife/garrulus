import { afterEach, describe, expect, it, vi } from 'vitest';
import { translateText, translatorAvailable } from './translate';

afterEach(() => vi.unstubAllGlobals());

describe('translate', () => {
  it('unavailableWithoutTranslatorGlobal', async () => {
    expect(await translatorAvailable('zh')).toBe('unavailable');
    await expect(translateText('x', 'zh')).rejects.toThrow();
  });

  it('usesTranslatorWhenPresent', async () => {
    const create = vi.fn(async () => ({ translate: async (t: string) => `EN:${t}` }));
    const availability = vi.fn(async () => 'downloadable');
    vi.stubGlobal('Translator', { availability, create });
    expect(await translatorAvailable('zh')).toBe('downloadable');
    expect(availability).toHaveBeenCalledWith({ sourceLanguage: 'zh', targetLanguage: 'en' });
    expect(await translateText('你好', 'zh')).toBe('EN:你好');
    expect(create).toHaveBeenCalledWith({ sourceLanguage: 'zh', targetLanguage: 'en' });
  });
});
