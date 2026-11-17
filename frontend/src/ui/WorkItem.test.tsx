import { screen } from '@testing-library/react';
import { renderApp } from '../test/render';
import { work } from '../test/server';
import { WorkItem } from './WorkItem';

const name = { id: 5, name: 'Tenumembrana parva', placementMethod: 'MATCH' as const, kind: 'SCIENTIFIC', rank: 'SPECIES', actTypes: ['SP_NOV'] };

describe('WorkItem', () => {
  it('shows the title link, the meta line and the main names with their act markers', () => {
    const w = { ...work(3, 'A new species of Tenumembrana parva'), mainNames: [name], hasActs: true,
      titleMentions: [{ id: 1, nameId: 5, startCp: 17, endCp: 35, verbatim: '' }], volume: '12', pages: '1-9' };
    const { container } = renderApp(<WorkItem work={w} />);
    expect(screen.getByRole('link', { name: /A new species of/ })).toHaveAttribute('href', '/work/3');
    expect(screen.getByText(/A\. Author/)).toBeInTheDocument();
    const link = screen.getByRole('link', { name: 'Tenumembrana parva' });
    expect(link).toHaveAttribute('href', '/name/5');
    expect(link.querySelector('i')).toHaveTextContent('Tenumembrana parva');
    expect(screen.getByText('sp. nov.')).toHaveClass('gr-act');
    expect(screen.queryByText('new names')).toBeNull();
    expect(container.querySelectorAll('i').length).toBe(2);
  });

  it('shows "new names" when the work has acts but no name carries one', () => {
    const w = { ...work(4, 'Revision'), hasActs: true, mainNames: [{ ...name, actTypes: [] }] };
    renderApp(<WorkItem work={w} />);
    expect(screen.getByText('new names')).toHaveClass('gr-act');
  });
});
