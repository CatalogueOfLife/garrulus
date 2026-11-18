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

  it('setsTheActMarkerInRedAfterTheName', () => {
    const text = 'Here 😀 Diploneis langebertalotii Van de Vijver, sp. nov. is new.';
    const start = Array.from(text.slice(0, text.indexOf('Diploneis'))).length;
    const act = Array.from(text.slice(0, text.indexOf('sp. nov.'))).length;
    const block: TextBlock = {
      id: 'b9', type: 'ABSTRACT', level: 0, sectionPath: [], text,
      mentions: [{ id: 1, nameId: 7, startCp: start, endCp: start + 25, verbatim: 'Diploneis langebertalotii', actType: 'SP_NOV' }],
      acts: [{ mentionId: 1, startCp: act, endCp: act + 8, actType: 'SP_NOV' }],
    };
    renderApp(<HighlightedText block={block} />);
    const marker = screen.getByText('sp. nov.');
    expect(marker).toHaveClass('gr-act-mark');
    expect(marker.closest('a')).toBeNull();
    expect(screen.getByRole('link', { name: 'Diploneis langebertalotii' })).toHaveClass('gr-mark-act');
    expect(marker.closest('p')?.textContent).toBe(text);
  });

  it('doesNotItaliciseAFamilyMention', () => {
    const block: TextBlock = {
      id: 'b2', type: 'paragraph', level: 0, sectionPath: [], text: 'Carabidae are beetles.',
      mentions: [{ id: 1, nameId: 3, startCp: 0, endCp: 9, verbatim: 'Carabidae' }],
    };
    renderApp(<HighlightedText block={block} names={new Map([[3, { rank: 'family' }]])} />);
    expect(screen.getByRole('link', { name: 'Carabidae' }).querySelector('i')).toBeNull();
  });

  it('italicisesASpeciesMention', () => {
    const block: TextBlock = {
      id: 'b3', type: 'paragraph', level: 0, sectionPath: [], text: 'Carabus auratus is common.',
      mentions: [{ id: 1, nameId: 3, startCp: 0, endCp: 15, verbatim: 'Carabus auratus' }],
    };
    renderApp(<HighlightedText block={block} names={new Map([[3, { rank: 'species' }]])} />);
    expect(screen.getByRole('link', { name: 'Carabus auratus' }).querySelector('i')).not.toBeNull();
  });

  it('rendersAsSpanInsideAHeadingWithoutNestingWarnings', () => {
    const err = vi.spyOn(console, 'error').mockImplementation(() => {});
    const block: TextBlock = { id: 'h', type: 'title', level: 1, sectionPath: [], text: 'Carabus', mentions: [] };
    renderApp(<h5><HighlightedText block={block} as="span" /></h5>);
    expect(err).not.toHaveBeenCalled();
    err.mockRestore();
  });
});
