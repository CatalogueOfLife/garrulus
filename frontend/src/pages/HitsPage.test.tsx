import { screen, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { HitsPage } from './HitsPage';
import { server, meBody, work } from '../test/server';
import { renderApp } from '../test/render';

describe('HitsPage', () => {
  it('filtersBySubscriptionFromUrl', async () => {
    const seen: URLSearchParams[] = [];
    server.use(
      http.get('/api/me', () => HttpResponse.json(meBody)),
      http.get('/api/me/hits', ({ request }) => {
        seen.push(new URL(request.url).searchParams);
        return HttpResponse.json({ limit: 20, offset: 0, results: [
          { id: 1, kind: 'ARTICLE', names: [], subscriptionId: 3, subscriptionLabel: 'Carabus', created: '2026-11-16T07:00:00Z', work: work(10, 'A carabid paper') },
          { id: 2, kind: 'NEW_NAMES', names: [], subscriptionId: 3, subscriptionLabel: 'Carabus', created: '2026-11-15T07:00:00Z', work: work(11, 'A new species') },
          { id: 3, kind: 'ARTICLE', names: [], subscriptionId: 3, subscriptionLabel: 'Carabus', created: '2026-11-14T07:00:00Z', work: null },
        ] });
      }),
    );
    renderApp(<HitsPage />, '/hits?subscription=3');
    expect(await screen.findByText('A carabid paper')).toBeInTheDocument();
    expect(seen[0].get('subscription')).toBe('3');
    expect(screen.getByText('New names')).toBeInTheDocument();
    expect(screen.getAllByText('New names')).toHaveLength(1);
    expect(screen.getByText(/no longer listed/)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Show all hits' })).toHaveAttribute('href', '/hits');
  });

  it('labels names with their acts, acts and main subjects first, at most ten', async () => {
    const names = Array.from({ length: 14 }, (_, i) => ({
      nameStringId: i + 1, name: `Name n${i + 1}`, mainSubject: i + 1 === 9,
      actTypes: i + 1 === 12 ? ['SP_NOV'] : i + 1 === 13 ? ['COMB_NOV', 'STAT_REV'] : [],
    }));
    server.use(
      http.get('/api/me', () => HttpResponse.json(meBody)),
      http.get('/api/me/hits', () => HttpResponse.json({ limit: 20, offset: 0, results: [
        { id: 1, kind: 'NEW_NAMES', names, subscriptionId: 3, subscriptionLabel: 'Zootaxa', created: '2026-11-16T07:00:00Z', work: work(10, 'A checklist') },
      ] })),
    );
    renderApp(<HitsPage />, '/hits');
    const list = await screen.findByLabelText('Names of this hit');
    expect(screen.getByText('New names')).toBeInTheDocument();
    expect(within(list).getByText('sp. nov.')).toBeInTheDocument();
    expect(within(list).getByText('comb. nov.')).toBeInTheDocument();
    expect(within(list).getByText('stat. rev.')).toBeInTheDocument();
    expect(within(list).queryByText(/^new$/i)).toBeNull();
    const shown = within(list).getAllByText(/^Name n\d+$/).map((e) => e.textContent);
    expect(shown).toEqual(['Name n12', 'Name n13', 'Name n9', 'Name n1', 'Name n2', 'Name n3', 'Name n4', 'Name n5',
      'Name n6', 'Name n7']);
    expect(within(list).getByText('+4 more')).toBeInTheDocument();
  });

  it('asks to sign in when signed out', async () => {
    renderApp(<HitsPage />, '/hits');
    expect(await screen.findByRole('link', { name: 'sign in' })).toHaveAttribute('href', '/signin?next=' + encodeURIComponent('/hits'));
  });
});
