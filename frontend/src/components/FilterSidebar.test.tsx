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
    const { container } = renderApp(
      <FilterSidebar filter={{}} taxGroups={[...taxGroups].reverse()} onChange={noop} facets={{ taxgroup: [{ value: 'Insects', count: 5 }] }} />,
    );
    // the icon is decoration next to the visible label: the row is named once
    const insects = screen.getByRole('button', { name: 'Insects 5' });
    expect(insects.querySelector('img')).toHaveAttribute('src', 'https://icons.example/insects.svg');
    expect(insects.querySelector('img')).toHaveAttribute('alt', '');
    expect(screen.getByRole('button', { name: 'Animals' })).toBeInTheDocument();
    expect(container.querySelectorAll('img:not([alt=""])')).toHaveLength(0);
    // hierarchical: the parent comes before its child whatever the input order
    const rows = screen.getAllByRole('button', { name: /^(Animals|Insects)/ }).map((b) => b.textContent);
    expect(rows.findIndex((t) => t?.startsWith('Animals'))).toBeLessThan(rows.findIndex((t) => t?.startsWith('Insects')));
  });

  it('disciplineIsChosenByName', async () => {
    server.use(xrHandler);
    const onChange = vi.fn();
    renderApp(<FilterSidebar filter={{ subfield: '1109' }} taxGroups={taxGroups} onChange={onChange} facets={{}} />);
    const select = screen.getByLabelText('Discipline') as HTMLSelectElement;
    expect(select.value).toBe('1109');
    expect(await screen.findByRole('option', { name: 'Insect Science' })).toHaveAttribute('title', '1109');
    await userEvent.selectOptions(select, 'Ecology');
    expect(onChange).toHaveBeenCalledWith({ subfield: '2303', offset: undefined });
    await userEvent.selectOptions(select, 'Any');
    expect(onChange).toHaveBeenLastCalledWith({ subfield: undefined, offset: undefined });
  });
});
