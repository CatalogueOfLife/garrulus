import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderApp } from '../test/render';
import { FacetGroup } from './FacetGroup';

const items = [{ value: 'Animals', label: 'Animals', count: 11102 }, { value: 'Plants', label: 'Plants', count: 3 }];

describe('FacetGroup', () => {
  it('formats the counts and selects a value', async () => {
    const onSelect = vi.fn();
    renderApp(<FacetGroup title="Group" items={items} onSelect={onSelect} />);
    expect(screen.getByText('11,102')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: /Animals/ }));
    expect(onSelect).toHaveBeenCalledWith('Animals');
  });

  it('marks the current value and clears it on a second click', async () => {
    const onSelect = vi.fn();
    renderApp(<FacetGroup title="Group" items={items} current="Animals" onSelect={onSelect} />);
    const row = screen.getByRole('button', { name: /Animals/ });
    expect(row).toHaveAttribute('aria-current', 'true');
    expect(screen.getByRole('button', { name: /Plants/ })).not.toHaveAttribute('aria-current');
    await userEvent.click(row);
    expect(onSelect).toHaveBeenCalledWith(undefined);
  });
});
