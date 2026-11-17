import { screen, within } from '@testing-library/react';
import { readFileSync } from 'node:fs';
import { DesignPage } from './DesignPage';
import { parseTokens } from '../styles/tokenValues';
import { renderApp } from '../test/render';

// tokens.css?raw comes back empty under vitest's css:false, so hand the page the real file.
vi.mock('../styles/tokens.css?raw', async () => {
  const { readFileSync: read } = await import('node:fs');
  return { default: read('src/styles/tokens.css', 'utf8') };
});

const tokens = parseTokens(readFileSync('src/styles/tokens.css', 'utf8'));
const colours = Object.keys(tokens.light).filter((k) => /^--gr-(paper|ink|grey|rule-soft|red)$/.test(k));

describe('DesignPage', () => {
  it('showsTheFiveSections', () => {
    renderApp(<DesignPage />, '/design');
    for (const name of ['Colour', 'Type', 'Logo', 'Components', 'Patterns']) {
      expect(screen.getByRole('heading', { level: 2, name })).toBeInTheDocument();
    }
  });

  it('showsASwatchWithItsHexForEveryColourTokenInLightAndDark', () => {
    renderApp(<DesignPage />, '/design');
    expect(colours).toHaveLength(5);
    for (const scheme of ['light', 'dark'] as const) {
      for (const token of colours) {
        const swatch = screen.getByTestId(`swatch-${scheme}${token}`);
        expect(within(swatch).getByText(token)).toBeInTheDocument();
        expect(within(swatch).getByText(tokens[scheme][token].toUpperCase())).toBeInTheDocument();
      }
    }
    expect(screen.getByTestId('swatches-dark').closest('[data-mantine-color-scheme="dark"]')).not.toBeNull();
  });

  it('setsTheTypeScaleAndTheMarkAtFourSizes', () => {
    renderApp(<DesignPage />, '/design');
    expect(screen.getByText('--gr-type-title')).toBeInTheDocument();
    expect(screen.getByText('--gr-type-small')).toBeInTheDocument();
    const sizes = screen.getAllByRole('img', { name: 'Garrulus mark' }).map((s) => s.getAttribute('width'));
    expect(sizes).toEqual(['260', '64', '32', '16']);
  });

  it('rendersTheSampleWorksWithTheirNamesInItalics', () => {
    renderApp(<DesignPage />, '/design');
    expect(screen.getAllByText('Hemiphyllodactylus').some((e) => e.tagName === 'I')).toBe(true);
    expect(screen.getAllByText('sp. nov.').length).toBeGreaterThan(0);
    expect(screen.getByRole('heading', { level: 3, name: /Syntelia/ })).toBeInTheDocument();
  });

  it('doesNotTakeFocusOrTrapItInTheOpenMenuDemo', () => {
    renderApp(<DesignPage />, '/design');
    expect(screen.getByRole('menuitem', { name: 'Account' })).toBeInTheDocument();
    expect(document.activeElement).toBe(document.body);
  });

  it('listsTheItalicsRuleAsATable', () => {
    renderApp(<DesignPage />, '/design');
    const table = screen.getByRole('table', { name: 'Italics rule' });
    expect(within(table).getByText('Family and above')).toBeInTheDocument();
    expect(within(table).getByText('OTU codes')).toBeInTheDocument();
  });
});
