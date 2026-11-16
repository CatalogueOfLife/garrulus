import { screen } from '@testing-library/react';
import { ClassificationPath, rootFirst } from './ClassificationPath';
import { renderApp } from '../test/render';

const rootToLeaf = [
  { id: 'A', name: 'Animalia', rank: 'kingdom' }, { id: 'P', name: 'Arthropoda', rank: 'phylum' },
  { id: 'F', name: 'Carabidae', rank: 'family' }, { id: 'G', name: 'Carabus', rank: 'genus' },
];
const names = () => screen.getByTestId('classification-path').textContent;

describe('ClassificationPath', () => {
  it('rendersRootFirstFromRootFirstInput', () => {
    renderApp(<ClassificationPath items={rootToLeaf} />);
    expect(names()).toBe('Animalia›Arthropoda›Carabidae›Carabus');
  });
  it('rendersRootFirstFromLowestFirstInput', () => {
    renderApp(<ClassificationPath items={[...rootToLeaf].reverse()} />);
    expect(names()).toBe('Animalia›Arthropoda›Carabidae›Carabus');
    expect(screen.getByRole('link', { name: 'Carabus' })).toHaveAttribute('href', '/taxon/G');
  });
  it('withoutRanksUsesTheDeclaredOrder', () => {
    const bare = rootToLeaf.map(({ id, name }) => ({ id, name }));
    expect(rootFirst([...bare].reverse()).map((i) => i.name)).toEqual(['Animalia', 'Arthropoda', 'Carabidae', 'Carabus']);
    expect(rootFirst(bare, 'root-first').map((i) => i.name)).toEqual(['Animalia', 'Arthropoda', 'Carabidae', 'Carabus']);
  });
  it('keepsUnknownRanksInPlace', () => {
    const items = [{ name: 'X', rank: 'weird' }, { name: 'Carabidae', rank: 'family' }, { name: 'Animalia', rank: 'kingdom' }];
    expect(rootFirst(items).map((i) => i.name)).toEqual(['Animalia', 'Carabidae', 'X']);
  });
  it('rendersNothingWhenEmpty', () => {
    renderApp(<ClassificationPath items={[]} />);
    expect(screen.queryByTestId('classification-path')).toBeNull();
  });
});
