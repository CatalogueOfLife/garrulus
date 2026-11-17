import { isItalicName } from './nameStyle';

describe('isItalicName', () => {
  it.each([
    ['Abies alba', { rank: 'SPECIES' }, true],
    ['Abies', { rank: 'genus' }, true],
    ['Pinaceae', { rank: 'FAMILY' }, false],
    ['Abies', { rank: 'UNRANKED', usageRank: 'genus' }, true],
    ['Abies alba', {}, true],
    ['Synteliidae', {}, false],
    ['Syntelia', {}, false],
    ['BOLD:AAA1234', { kind: 'OTU' }, false],
    ['Abies sect. x', { rank: 'section' }, true],
    ['Pinales', { rank: 'order' }, false],
  ])('%s %j -> %s', (name, info, expected) => {
    expect(isItalicName(name, info)).toBe(expected);
  });
});
