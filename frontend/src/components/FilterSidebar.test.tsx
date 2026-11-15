import { screen } from '@testing-library/react';
import { FilterSidebar } from './FilterSidebar';
import { renderApp } from '../test/render';
import { server, taxGroups, xrHandler } from '../test/server';

const noop = () => {};

describe('FilterSidebar', () => {
  it('facetCountsShowNextToOptions', () => {
    server.use(xrHandler);
    renderApp(
      <FilterSidebar
        filter={{}}
        taxGroups={taxGroups}
        onChange={noop}
        facets={{
          code: [{ value: 'ZOOLOGICAL', count: 12 }, { value: 'BOTANICAL', count: 3 }],
          journalType: [{ value: 'TAXONOMIC', count: 9 }],
          hasActs: [{ value: 'true', count: 4 }],
        }}
      />,
    );
    expect(screen.getByRole('radio', { name: 'Zoological (12)' })).toBeInTheDocument();
    expect(screen.getByRole('radio', { name: 'Botanical (3)' })).toBeInTheDocument();
    expect(screen.getByRole('radio', { name: 'Taxonomic (9)' })).toBeInTheDocument();
    expect(screen.getByRole('checkbox', { name: 'Has nomenclatural acts (4)' })).toBeInTheDocument();
  });

  it('taxgroupOptionsShowIcons', () => {
    server.use(xrHandler);
    renderApp(<FilterSidebar filter={{}} taxGroups={[...taxGroups].reverse()} onChange={noop} facets={{ taxgroup: [{ value: 'Insects', count: 5 }] }} />);
    const img = screen.getByAltText('Insects');
    expect(img).toHaveAttribute('src', 'https://icons.example/insects.svg');
    expect(screen.getByAltText('Animals')).toBeInTheDocument();
    expect(screen.getByRole('radio', { name: /Insects \(5\)/ })).toBeInTheDocument();
    // hierarchical: the parent comes before its child whatever the input order
    const names = screen.getAllByRole('radio').map((r) => (r as HTMLInputElement).value);
    expect(names.indexOf('Animals')).toBeLessThan(names.indexOf('Insects'));
  });
});
