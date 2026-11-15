import { screen } from '@testing-library/react';
import { HighlightedText } from './HighlightedText';
import { renderApp } from '../test/render';
import type { TextBlock } from '../api';

describe('HighlightedText', () => {
  it('openWorkHighlightsMentions', () => {
    // '𝔄' and the emoji are astral: two UTF-16 units, one code point each
    const text = '𝔄 😀 Carabus auratus is common; Carabus nemoralis too.';
    const cp = Array.from(text);
    const s1 = cp.join('').indexOf('Carabus auratus');
    const start1 = Array.from(text.slice(0, s1)).length;
    const s2 = text.indexOf('Carabus nemoralis');
    const start2 = Array.from(text.slice(0, s2)).length;
    const block: TextBlock = {
      id: 'b1', type: 'paragraph', level: 0, sectionPath: [], text,
      mentions: [
        { id: 1, nameId: 11, startCp: start1, endCp: start1 + 15, verbatim: 'Carabus auratus' },
        { id: 2, nameId: 12, startCp: start2, endCp: start2 + 17, verbatim: 'Carabus nemoralis' },
      ],
    };
    renderApp(<HighlightedText block={block} />);
    const a = screen.getByRole('link', { name: 'Carabus auratus' });
    expect(a).toHaveAttribute('href', '/name/11');
    expect(screen.getByRole('link', { name: 'Carabus nemoralis' })).toHaveAttribute('href', '/name/12');
    expect(a.closest('p')?.textContent).toBe(text);
  });

  it('rendersAsSpanInsideAHeadingWithoutNestingWarnings', () => {
    const err = vi.spyOn(console, 'error').mockImplementation(() => {});
    const block: TextBlock = { id: 'h', type: 'title', level: 1, sectionPath: [], text: 'Carabus', mentions: [] };
    renderApp(<h5><HighlightedText block={block} as="span" /></h5>);
    expect(err).not.toHaveBeenCalled();
    err.mockRestore();
  });
});
