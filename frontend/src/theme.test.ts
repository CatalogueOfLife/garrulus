import { theme } from './theme';

describe('theme', () => {
  it('uses the brand colours', () => {
    expect(theme.primaryColor).toBe('jay');
    expect(theme.colors!.jay![6]).toBe('#2A6FBA');
    expect(theme.colors!.fawn![0]).toBe('#FAF4F1');
    expect(theme.colors!.dark![7]).toBe('#201E1D');
  });
});
