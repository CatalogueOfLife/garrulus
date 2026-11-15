import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { SearchPage } from './SearchPage';
import { server, work, emptyPage, taxGroupHandler, xrHandler } from '../test/server';
import { renderApp } from '../test/render';

describe('SearchPage', () => {
  it('filtersRoundTripThroughUrl', async () => {
    const seen: string[] = [];
    server.use(
      xrHandler,
      taxGroupHandler,
      http.get('/api/works', ({ request }) => {
        seen.push(new URL(request.url).search);
        return HttpResponse.json({ ...emptyPage, total: 1, results: [work(1, 'Carabid revision')] });
      }),
    );
    renderApp(<SearchPage />, '/?code=ZOOLOGICAL&yearFrom=2020&hasActs=true');
    expect(await screen.findByText('Carabid revision')).toBeInTheDocument();
    // the URL state reaches the API under the same names
    const q = new URLSearchParams(seen[0]);
    expect(q.get('code')).toBe('ZOOLOGICAL');
    expect(q.get('yearFrom')).toBe('2020');
    expect(q.get('hasActs')).toBe('true');
    expect(q.getAll('facet')).toContain('taxgroup');
    // and the sidebar shows it
    expect(screen.getByRole('radio', { name: /Zoological/ })).toBeChecked();
    expect(screen.getByLabelText('Year from')).toHaveValue('2020');
    expect(screen.getByRole('checkbox', { name: /Has nomenclatural acts/ })).toBeChecked();
    // a change goes back to the URL
    await userEvent.click(screen.getByRole('radio', { name: /Taxonomic/ }));
    await waitFor(() => expect(screen.getByTestId('location').textContent).toContain('journalType=TAXONOMIC'));
    expect(screen.getByTestId('location').textContent).toContain('code=ZOOLOGICAL');
  });

  it('emptyResultShowsHint', async () => {
    server.use(xrHandler, taxGroupHandler, http.get('/api/works', () => HttpResponse.json(emptyPage)));
    renderApp(<SearchPage />, '/?q=zzz');
    expect(await screen.findByText(/No works match these filters/)).toBeInTheDocument();
  });

  it('apiErrorShowsMessageNotCrash', async () => {
    server.use(
      xrHandler,
      taxGroupHandler,
      http.get('/api/works', () => HttpResponse.json({ status: 400, error: 'yearFrom must be a number' }, { status: 400 })),
    );
    renderApp(<SearchPage />, '/?yearFrom=abc');
    expect(await screen.findByText('yearFrom must be a number')).toBeInTheDocument();
    expect(screen.getByLabelText('Year from')).toBeInTheDocument();
  });

  it('typingAYearSendsOneRequestWithTheFinalValue', async () => {
    const seen: (string | null)[] = [];
    server.use(
      xrHandler,
      taxGroupHandler,
      http.get('/api/works', ({ request }) => {
        seen.push(new URL(request.url).searchParams.get('yearFrom'));
        return HttpResponse.json(emptyPage);
      }),
    );
    renderApp(<SearchPage />);
    await screen.findByText(/No works match/);
    expect(seen).toEqual([null]);
    const input = screen.getByLabelText('Year from');
    await userEvent.type(input, '2020');
    await userEvent.tab();
    await waitFor(() => expect(seen).toEqual([null, '2020']));
    expect(screen.getByTestId('location').textContent).toBe('/?yearFrom=2020');
  });
});
