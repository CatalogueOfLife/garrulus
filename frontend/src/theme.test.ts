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

  it('leaves no Mantine grey in outline buttons, placeholders, disabled states or red shades', () => {
    const r = cssVariablesResolver(full);
    for (const bag of [r.light, r.dark]) {
      expect(bag['--mantine-color-default-color']).toBe('var(--gr-ink)');
      expect(bag['--mantine-color-default-hover']).toBe('transparent');
      expect(bag['--mantine-color-placeholder']).toBe('var(--gr-grey)');
      expect(bag['--mantine-color-disabled']).toBe('transparent');
      expect(bag['--mantine-color-disabled-color']).toBe('var(--gr-grey)');
      expect(bag['--mantine-color-red-6']).toBe('var(--gr-red)');
      expect(bag['--mantine-color-red-outline-hover']).toBe('transparent');
      expect(bag['--input-asterisk-color']).toBe('var(--gr-ink)');
    }
    expect(r.light['--mantine-color-gray-5']).toBe('var(--gr-grey)');
    expect(r.dark['--mantine-color-dark-3']).toBe('var(--gr-grey)');
  });

  it('drops the old palette', () => {
    expect(theme.colors).not.toHaveProperty('jay');
    expect(theme.colors).not.toHaveProperty('fawn');
  });
});
