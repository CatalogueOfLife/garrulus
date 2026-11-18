import { paragraphs } from './paragraphs';

const span = (id: number, startCp: number, endCp: number) => ({ id, nameId: id, startCp, endCp, verbatim: 'x' });

describe('paragraphs', () => {
  it('splitsAtBlankLinesAndMovesTheSpans', () => {
    // the second paragraph starts at code point 19; "Then 😀 " is 7 code points, so Picea is 26..31 in the whole text
    const p = paragraphs('Abies alba here.\n\n\nThen 😀 Picea.\n\n', [span(1, 0, 10), span(2, 26, 31)]);
    expect(p.map((b) => b.text)).toEqual(['Abies alba here.', 'Then 😀 Picea.']);
    expect(p[0].mentions).toEqual([span(1, 0, 10)]);
    expect(p[1].mentions).toEqual([span(2, 7, 12)]);
    expect(Array.from(p[1].text).slice(7, 12).join('')).toBe('Picea');
  });

  it('keepsSingleLineBreaksAndDropsEmptyParagraphs', () => {
    expect(paragraphs('one\ntwo').map((b) => b.text)).toEqual(['one\ntwo']);
    expect(paragraphs('  \n\n  ')).toEqual([]);
    expect(paragraphs('')).toEqual([]);
  });

  it('dropsASpanAcrossABreak', () => {
    expect(paragraphs('ab\n\ncd', [span(1, 1, 5)]).flatMap((b) => b.mentions)).toEqual([]);
  });
});
