import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { JournalsPage } from './JournalsPage';
import { renderApp } from '../test/render';
import { server } from '../test/server';

const item = (id: number, title: string, over = {}) => ({
  id, title, tier: 'CORE', journalType: 'TAXONOMIC', subfields: [], taxgroups: [], codes: [], worksPerYear: 120, ...over,
});

describe('JournalsPage', () => {
  it('listsJournalsWithBadgesAndLinks', async () => {
    server.use(http.get('/api/sources', () => HttpResponse.json({
      total: 2, limit: 50, offset: 0, results: [item(7, 'Zootaxa'), item(8, 'Nature', { tier: 'BROAD', journalType: 'GENERAL' })],
    })));
    renderApp(<JournalsPage />, '/journals');
    const link = await screen.findByRole('link', { name: 'Zootaxa' });
    expect(link).toHaveAttribute('href', '/source/7');
    expect(screen.getAllByText(/Broad/).length).toBeGreaterThan(0);
    expect(screen.getAllByText(/General/).length).toBeGreaterThan(0);
    expect(screen.getAllByText(/120 papers a year/)).toHaveLength(2);
  });

  it('sendsTitleTierAndTypeFiltersAndPaging', async () => {
    const seen: string[] = [];
    server.use(http.get('/api/sources', ({ request }) => {
      seen.push(new URL(request.url).search);
      return HttpResponse.json({ total: 120, limit: 50, offset: 0, results: [item(7, 'Zootaxa')] });
    }));
    renderApp(<JournalsPage />, '/journals');
    await screen.findByRole('link', { name: 'Zootaxa' });
    const user = userEvent.setup();
    await user.type(screen.getByLabelText('Title'), 'zoo');
    await waitFor(() => expect(screen.getByTestId('location')).toHaveTextContent('q=zoo'));
    await user.click(screen.getByLabelText('Broad'));
    await user.click(screen.getByLabelText('General'));
    await user.click(await screen.findByRole('button', { name: '2' }));
    await waitFor(() => expect(seen.at(-1)).toContain('offset=50'));
    const last = new URLSearchParams(seen.at(-1));
    expect(last.get('q')).toBe('zoo');
    expect(last.get('tier')).toBe('BROAD');
    expect(last.get('journalType')).toBe('GENERAL');
    expect(last.get('limit')).toBe('50');
  });

  it('readsFiltersFromTheUrl', async () => {
    let search = '';
    server.use(http.get('/api/sources', ({ request }) => {
      search = new URL(request.url).search;
      return HttpResponse.json({ total: 0, limit: 50, offset: 0, results: [] });
    }));
    renderApp(<JournalsPage />, '/journals?q=bio&tier=CORE');
    expect(await screen.findByText('No journals match.')).toBeInTheDocument();
    expect(search).toContain('q=bio');
    expect(search).toContain('tier=CORE');
  });
});
