import { screen } from '@testing-library/react';
import { MetaLine } from './MetaLine';
import { renderApp } from '../test/render';

const text = (el: HTMLElement) => (el.querySelector('p')?.textContent ?? '').replace(/\s+/g, ' ').trim();

describe('MetaLine', () => {
  it('showsAllPartsInOrder', () => {
    const { container } = renderApp(
      <MetaLine authors={['Dinh', 'Nishikawa', 'Luu']} authorCount={6} source={{ id: 7, title: 'ZooKeys' }}
        volume="1297" issue="3" pages="311-337" date="2026-11-16" year={2026} textLevel="FULLTEXT" />,
    );
    expect(text(container)).toBe('Dinh, Nishikawa, Luu et al. · ZooKeys 1297(3): 311–337 · 16 Nov 2026 · Full text');
    expect(screen.getByRole('link', { name: 'ZooKeys' })).toHaveAttribute('href', '/source/7');
  });
  it('dropsEmptyPartsWithoutStraySeparators', () => {
    const { container } = renderApp(<MetaLine authors={[]} authorCount={0} date="2026-11-16" textLevel="ABSTRACT_ONLY" />);
    expect(text(container)).toBe('16 Nov 2026 · Abstract');
  });
  it('showsNothingForNoText', () => {
    const { container } = renderApp(<MetaLine authors={[]} authorCount={0} year={2020} textLevel="NO_TEXT" />);
    expect(text(container)).toBe('2020');
  });
  it('addsEtAlOnlyWhenAuthorsAreCut', () => {
    const { container } = renderApp(<MetaLine authors={['A', 'B']} authorCount={2} />);
    expect(text(container)).toBe('A, B');
  });
  it('volumeWithoutPages', () => {
    const { container } = renderApp(<MetaLine authors={[]} authorCount={0} source={{ id: 7, title: 'ZooKeys' }} volume="1297" />);
    expect(text(container)).toBe('ZooKeys 1297');
  });
});
