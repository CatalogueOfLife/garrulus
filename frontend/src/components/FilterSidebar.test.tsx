import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
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
    expect(screen.getByRole('button', { name: 'Zoological 12' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Botanical 3' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Taxonomic 9' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Has nomenclatural acts 4' })).toBeInTheDocument();
  });

  it('taxgroupOptionsShowIcons', () => {
    server.use(xrHandler);
    renderApp(<FilterSidebar filter={{}} taxGroups={[...taxGroups].reverse()} onChange={noop} facets={{ taxgroup: [{ value: 'Insects', count: 5 }] }} />);
    const img = screen.getByAltText('Insects');
    expect(img).toHaveAttribute('src', 'https://icons.example/insects.svg');
    expect(screen.getByAltText('Animals')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Insects.*5/ })).toBeInTheDocument();
    // hierarchical: the parent comes before its child whatever the input order
    const names = screen.getAllByAltText(/Animals|Insects/).map((i) => i.getAttribute('alt'));
    expect(names.indexOf('Animals')).toBeLessThan(names.indexOf('Insects'));
  });

  it('subfieldIsChosenByName', async () => {
    server.use(xrHandler);
    const onChange = vi.fn();
    renderApp(<FilterSidebar filter={{ subfield: '1109' }} taxGroups={taxGroups} onChange={onChange} facets={{}} />);
    const select = screen.getByLabelText('Subfield') as HTMLSelectElement;
    expect(select.value).toBe('1109');
    expect(screen.getByRole('option', { name: 'Insect Science' })).toHaveAttribute('title', '1109');
    await userEvent.selectOptions(select, 'Ecology');
    expect(onChange).toHaveBeenCalledWith({ subfield: '2303', offset: undefined });
    await userEvent.selectOptions(select, 'Any');
    expect(onChange).toHaveBeenLastCalledWith({ subfield: undefined, offset: undefined });
  });
});
