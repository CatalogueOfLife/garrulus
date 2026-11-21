/** The English name of a language code (ISO 639-1, or a BCP 47 tag); the code itself when it is unknown. */
export function languageName(code: string): string {
  try {
    return new Intl.DisplayNames(['en'], { type: 'language' }).of(code) ?? code;
  } catch {
    return code;
  }
}

export const isEnglish = (code?: string | null) => !code || /^en(-|$)/i.test(code);
