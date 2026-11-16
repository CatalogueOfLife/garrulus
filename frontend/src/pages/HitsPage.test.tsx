import { screen } from '@testing-library/react';
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

  it('asks to sign in when signed out', async () => {
    renderApp(<HitsPage />, '/hits');
    expect(await screen.findByRole('link', { name: 'sign in' })).toHaveAttribute('href', '/signin?next=' + encodeURIComponent('/hits'));
  });
});
