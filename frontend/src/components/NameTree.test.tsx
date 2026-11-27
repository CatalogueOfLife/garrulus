import { screen } from '@testing-library/react';
import type { NameDetail } from '../api';
import { renderApp } from '../test/render';
import { NameTree, buildNameTree } from './NameTree';

const nm = (id: number, name: string, over: Partial<NameDetail> = {}): NameDetail => ({
  id, name, kind: 'SCIENTIFIC', placementMethod: 'MATCH', usageId: null, usageName: null, salience: 0.5,
  mainSubject: false, onlyReference: false, mentionCount: 1, actTypes: [], bestSnippet: '', classification: [], ...over,
});

describe('buildNameTree', () => {
  it('sharesTaxaAndMergesExactMatches', () => {
    // classifications come lowest first from the API
    const insecta = [{ id: 'I', name: 'Insecta', rank: 'class' }, { id: 'A', name: 'Animalia', rank: 'kingdom' }];
    const root = buildNameTree([
      nm(1, 'Insecta', { usageId: 'I', usageName: 'Insecta', usageRank: 'class', classification: [{ id: 'A', name: 'Animalia', rank: 'kingdom' }] }),
      nm(2, 'Leuctridae', { usageId: 'L', usageName: 'Leuctridae Klapálek, 1905', usageRank: 'family', classification: insecta }),
      nm(3, 'Paraleuctra nova', { placementMethod: 'GENUS', usageId: 'P', usageName: 'Paraleuctra', usageRank: 'genus',
        classification: [{ id: 'L', name: 'Leuctridae', rank: 'family' }, ...insecta] }),
      nm(4, 'Nowhere', { placementMethod: 'NONE' }),
    ]);
    expect(root.children.map((c) => c.key)).toEqual(['id:A']);
    const insectaNode = root.children[0].children[0];
    expect(insectaNode.names.map((n) => n.id)).toEqual([1]);
    const leuctridae = insectaNode.children[0];
    expect(leuctridae.names.map((n) => n.id)).toEqual([2]); // the usage name carries the authorship
    const paraleuctra = leuctridae.children[0];
    expect(paraleuctra.names).toEqual([]);
    expect(paraleuctra.children.map((c) => c.names[0].id)).toEqual([3]); // placed on its genus: a leaf below it
  });

  it('joinsTaxaToOneLineOnlyAtTheRoot', () => {
    // lowest first: Animalia > Insecta > Plecoptera > Leuctridae, the root line ends at the first name of the work
    const up = [{ id: 'P', name: 'Plecoptera', rank: 'order' }, { id: 'I', name: 'Insecta', rank: 'class' }, { id: 'A', name: 'Animalia', rank: 'kingdom' }];
    renderApp(<NameTree names={[
      nm(1, 'Insecta', { usageId: 'I', usageName: 'Insecta', usageRank: 'class', classification: [{ id: 'A', name: 'Animalia', rank: 'kingdom' }] }),
      nm(2, 'Leuctridae', { usageId: 'L', usageName: 'Leuctridae', usageRank: 'family', classification: up }),
    ]} />);
    const rows = [...screen.getByTestId('name-tree').querySelectorAll('li > span')].map((r) => r.textContent);
    expect(rows).toEqual(['Animalia', 'Insecta', 'Plecoptera', 'Leuctridae']); // the first name of the work on its own row
  });
});
