import { render, screen, within } from '@testing-library/react';
import { LanguageBreakdown } from './LanguageBreakdown';

describe('LanguageBreakdown', () => {
  it('countsPerLanguageAndYear', () => {
    render(<LanguageBreakdown languages={[
      { language: 'en', year: 2025, works: 10 }, { language: 'en', year: 2024, works: 5 }, { language: 'zh', year: 2025, works: 3 },
    ]} />);
    const t = screen.getByRole('table', { name: 'Papers per language and year' });
    const rows = within(t).getAllByRole('row');
    expect(within(rows[1]).getByRole('rowheader')).toHaveTextContent('English');
    expect(within(rows[1]).getAllByRole('cell').map((c) => c.textContent)).toEqual(['15', '10', '5']);
    expect(within(rows[2]).getAllByRole('cell').map((c) => c.textContent)).toEqual(['3', '3', '0']);
  });
  it('undecidedRow', () => {
    render(<LanguageBreakdown languages={[{ language: '', year: 2025, works: 4 }]} />);
    expect(screen.getByRole('rowheader', { name: 'Undecided' })).toBeInTheDocument();
  });
});
