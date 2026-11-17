/**
 * Reads the `--gr-*` custom properties out of tokens.css: `light` from the `:root` block, `dark` from the
 * `:root[data-mantine-color-scheme='dark']` block. Keys keep their leading `--` (for example `--gr-paper`).
 */
export function parseTokens(css: string): { light: Record<string, string>; dark: Record<string, string> } {
  const out = { light: {} as Record<string, string>, dark: {} as Record<string, string> };
  const clean = css.replace(/\/\*[\s\S]*?\*\//g, '');
  for (const m of clean.matchAll(/([^{}@;]+?)\s*\{([^{}]*)\}/g)) {
    const selector = m[1].trim();
    const target =
      selector === ':root'
        ? out.light
        : /^:root\[data-mantine-color-scheme=['"]dark['"]\]$/.test(selector)
          ? out.dark
          : null;
    if (!target) continue;
    for (const d of m[2].matchAll(/(--[\w-]+)\s*:\s*([^;]+);/g)) target[d[1]] = d[2].trim();
  }
  return out;
}
