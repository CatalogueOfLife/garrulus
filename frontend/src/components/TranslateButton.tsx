import { Button } from '@mantine/core';
import { useEffect, useState } from 'react';
import { translateText, translatorAvailable } from '../translate';
import { languageName } from '../languages';
import classes from '../ui/work.module.css';

/**
 * Translates a non-English text in the reader's browser (Chrome's on-device Translator); the result stands below the
 * original and is labelled. Without the API a hint points at the browser's own page translation.
 */
export function TranslateButton({ text, lang, showHint = true }: { text: string; lang: string; showHint?: boolean }) {
  const [state, setState] = useState<'checking' | 'available' | 'downloadable' | 'unavailable'>('checking');
  const [result, setResult] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [failed, setFailed] = useState(false);
  useEffect(() => { setResult(null); setFailed(false); }, [text, lang]);
  useEffect(() => {
    let live = true;
    translatorAvailable(lang).then((a) => { if (live) setState(a); });
    return () => { live = false; };
  }, [lang]);
  if (state === 'checking') return null;
  if (state === 'unavailable') {
    if (!showHint) return null;
    return <p className={classes.translateHint}>Your browser can translate this page: use its Translate option.</p>;
  }
  const run = async () => {
    setBusy(true);
    setFailed(false);
    try { setResult(await translateText(text, lang)); } catch { setFailed(true); } finally { setBusy(false); }
  };
  return (
    <div>
      {result === null ? (
        <Button variant="subtle" size="compact-sm" onClick={run} loading={busy}>
          Translate from {languageName(lang)}
        </Button>
      ) : (
        <div className={classes.translated} lang="en">
          <p className={classes.translateHint}>Machine translation, done in your browser</p>
          <p style={{ margin: 0 }}>{result}</p>
        </div>
      )}
      {failed ? <p className={classes.translateHint} role="alert">The browser could not translate this text.</p> : null}
    </div>
  );
}
