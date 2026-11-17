import { DEFAULT_THEME, mergeMantineTheme } from '@mantine/core';
import { cssVariablesResolver, theme } from './theme';

const full = mergeMantineTheme(DEFAULT_THEME, theme);

describe('theme', () => {
  it('uses Schibsted Grotesk and square corners', () => {
    expect(theme.fontFamily).toMatch(/^'Schibsted Grotesk'/);
    expect(theme.defaultRadius).toBe(0);
  });

  it('maps Mantine colours onto the tokens', () => {
    const r = cssVariablesResolver(full);
    expect(r.variables['--mantine-color-error']).toBe('var(--gr-red)');
    expect(r.light['--mantine-color-body']).toBe('var(--gr-paper)');
    expect(r.dark['--mantine-color-text']).toBe('var(--gr-ink)');
  });

  it('drops the old palette', () => {
    expect(theme.colors).not.toHaveProperty('jay');
    expect(theme.colors).not.toHaveProperty('fawn');
  });
});
