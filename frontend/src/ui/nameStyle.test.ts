import { isItalicName, nameRuns } from './nameStyle';

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

  // R26: ranks above genus are roman, every other known rank (name-parser 6 has many below genus) italic
  it.each([
    ['series_botany', true],
    ['forma_specialis', true],
    ['pathovar', true],
    ['infragenus', true],
    ['section_botany', true],
    ['cultivar', true],
    ['biovar', true],
    ['strain', true],
    ['supervariety', true],
    // name-parser 6 puts the zoological section between parvorder and superfamily, above genus
    ['section_zoology', false],
    ['series_zoology', false],
    ['family', false],
    ['order', false],
    ['tribe', false],
    ['realm', false],
    ['division_zoology', false],
    ['division', false],
    ['suprageneric_name', false],
    ['supergenus', false],
    ['falanx', false],
  ])('rank %s -> italic %s', (rank, expected) => {
    expect(isItalicName('Xus', { rank })).toBe(expected);
  });

  it('decides unknown ranks by the usage rank, then by shape', () => {
    expect(isItalicName('Xus', { rank: 'OTHER', usageRank: 'pathovar' })).toBe(true);
    expect(isItalicName('Xus yus', { rank: 'unranked', usageRank: 'family' })).toBe(false);
    expect(isItalicName('Xus yus', { rank: '', usageRank: null })).toBe(true);
    expect(isItalicName('Xus', { rank: null, usageRank: 'other' })).toBe(false);
  });

  it('keeps OTU codes roman whatever the rank', () => {
    expect(isItalicName('BOLD:AAA1234', { kind: 'OTU', rank: 'species' })).toBe(false);
  });
});

const html = (runs: { text: string; italic: boolean }[]) => runs.map((r) => (r.italic ? `<i>${r.text}</i>` : r.text)).join('');

describe('nameRuns', () => {
  it.each([
    ['Poa annua var. supina', '<i>Poa annua</i> var. <i>supina</i>'],
    ['Abies alba subsp. nebrodensis', '<i>Abies alba</i> subsp. <i>nebrodensis</i>'],
    ['Abies alba ssp. nebrodensis', '<i>Abies alba</i> ssp. <i>nebrodensis</i>'],
    ['Poa annua subvar. x-y', '<i>Poa annua</i> subvar. <i>x-y</i>'],
    ['Acer rubrum f. tomentosum', '<i>Acer rubrum</i> f. <i>tomentosum</i>'],
    ['Acer rubrum forma tomentosum', '<i>Acer rubrum</i> forma <i>tomentosum</i>'],
    ['Fusarium oxysporum f. sp. lycopersici', '<i>Fusarium oxysporum</i> f. sp. <i>lycopersici</i>'],
    ['Pseudomonas syringae pv. tomato', '<i>Pseudomonas syringae</i> pv. <i>tomato</i>'],
    ['Rhizobium leguminosarum bv. viciae', '<i>Rhizobium leguminosarum</i> bv. <i>viciae</i>'],
    ['Salmonella enterica sv. typhi', '<i>Salmonella enterica</i> sv. <i>typhi</i>'],
    ['Papilio dardanus morph hippocoon', '<i>Papilio dardanus</i> morph <i>hippocoon</i>'],
    ['Zygaena trifolii ab. minoides', '<i>Zygaena trifolii</i> ab. <i>minoides</i>'],
    ['Rosa gallica cv. Officinalis', '<i>Rosa gallica</i> cv. <i>Officinalis</i>'],
    ['Mentha ×piperita', '<i>Mentha</i> ×<i>piperita</i>'],
    ['Mentha × piperita', '<i>Mentha</i> × <i>piperita</i>'],
    ['×Agropogon', '×<i>Agropogon</i>'],
    ['Salix alba x fragilis', '<i>Salix alba</i> x <i>fragilis</i>'],
    ['Abies alba', '<i>Abies alba</i>'],
    ['Carabus (Megodontus) violaceus', '<i>Carabus (Megodontus) violaceus</i>'],
  ])('%s', (name, expected) => {
    expect(html(nameRuns(name, true))).toBe(expected);
  });

  it('keeps a roman name in one run', () => {
    expect(nameRuns('Pinaceae', false)).toEqual([{ text: 'Pinaceae', italic: false }]);
  });

  it('does not take a lone x at the start or end for a hybrid sign', () => {
    expect(html(nameRuns('Xus x', true))).toBe('<i>Xus x</i>');
  });

  it('gives back the text unchanged', () => {
    for (const s of ['Poa  annua var. supina', ' Abies alba ', 'Mentha ×piperita']) {
      expect(nameRuns(s, true).map((r) => r.text).join('')).toBe(s);
    }
  });
});
