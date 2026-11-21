// In-browser translation over Chrome's on-device Translator API. Nothing is sent by the page itself: no fetch.

export type Availability = 'available' | 'downloadable' | 'unavailable';

interface TranslatorInstance { translate(text: string): Promise<string> }
interface TranslatorStatic {
  availability(o: { sourceLanguage: string; targetLanguage: string }): Promise<string>;
  create(o: { sourceLanguage: string; targetLanguage: string }): Promise<TranslatorInstance>;
}

const api = (): TranslatorStatic | undefined =>
  (globalThis as unknown as { Translator?: TranslatorStatic }).Translator;

export async function translatorAvailable(from: string): Promise<Availability> {
  const t = api();
  if (!t) return 'unavailable';
  try {
    const a = await t.availability({ sourceLanguage: from, targetLanguage: 'en' });
    return a === 'available' ? 'available' : a === 'downloadable' || a === 'downloading' ? 'downloadable' : 'unavailable';
  } catch {
    return 'unavailable';
  }
}

export async function translateText(text: string, from: string): Promise<string> {
  const t = api();
  if (!t) throw new Error('This browser has no translator');
  const tr = await t.create({ sourceLanguage: from, targetLanguage: 'en' });
  return tr.translate(text);
}
