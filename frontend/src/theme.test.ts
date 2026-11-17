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

  it('maps Mantine red onto the token so dark mode stays readable', () => {
    const r = cssVariablesResolver(full);
    for (const bag of [r.variables, r.light, r.dark]) {
      expect(bag['--mantine-color-red-filled']).toBe('var(--gr-red)');
      expect(bag['--mantine-color-red-text']).toBe('var(--gr-red)');
    }
  });

  it('drops the old palette', () => {
    expect(theme.colors).not.toHaveProperty('jay');
    expect(theme.colors).not.toHaveProperty('fawn');
  });
});
