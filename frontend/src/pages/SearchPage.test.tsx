import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { SearchPage } from './SearchPage';
import { server, work, emptyPage, taxGroupHandler, xrHandler, sourcesHandler } from '../test/server';
import { renderApp } from '../test/render';

describe('SearchPage', () => {
  it('filtersRoundTripThroughUrl', async () => {
    const seen: string[] = [];
    server.use(
      xrHandler,
      taxGroupHandler,
      sourcesHandler(),
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
    expect(screen.getByRole('button', { name: /Zoological/ })).toHaveAttribute('aria-current', 'true');
    expect(screen.getByLabelText('Year from')).toHaveValue('2020');
    expect(screen.getByRole('button', { name: /Has nomenclatural acts/ })).toHaveAttribute('aria-current', 'true');
    // a change goes back to the URL
    await userEvent.click(screen.getByRole('button', { name: /Taxonomic/ }));
    await waitFor(() => expect(screen.getByTestId('location').textContent).toContain('journalType=TAXONOMIC'));
    expect(screen.getByTestId('location').textContent).toContain('code=ZOOLOGICAL');
  });

  it('emptyResultShowsHint', async () => {
    server.use(xrHandler, taxGroupHandler, sourcesHandler(), http.get('/api/works', () => HttpResponse.json(emptyPage)));
    renderApp(<SearchPage />, '/?q=zzz');
    expect(await screen.findByText(/No works match these filters/)).toBeInTheDocument();
  });

  it('apiErrorShowsMessageNotCrash', async () => {
    server.use(
      xrHandler,
      taxGroupHandler,
      sourcesHandler(),
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
      sourcesHandler(),
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

  it('ledeCountsTheJournalsFromTheSourcesTotal', async () => {
    server.use(xrHandler, taxGroupHandler, sourcesHandler(1234), http.get('/api/works', () => HttpResponse.json(emptyPage)));
    renderApp(<SearchPage />);
    expect(await screen.findByText(/Garrulus reads 1,234 journals of biodiversity science/)).toBeInTheDocument();
  });

  it('ledeHasNoNumberWhileLoadingOrOnError', async () => {
    server.use(xrHandler, taxGroupHandler, http.get('/api/sources', () => new HttpResponse(null, { status: 500 })),
      http.get('/api/works', () => HttpResponse.json(emptyPage)));
    renderApp(<SearchPage />);
    expect(screen.getByText(/^Garrulus reads journals of biodiversity science every day/)).toBeInTheDocument();
    await screen.findByText(/No works match/);
    expect(screen.getByText(/^Garrulus reads journals/)).toBeInTheDocument();
  });

  it('filtersPillTogglesTheSideColumn', async () => {
    server.use(xrHandler, taxGroupHandler, sourcesHandler(), http.get('/api/works', () => HttpResponse.json(emptyPage)));
    renderApp(<SearchPage />);
    const pill = screen.getByRole('button', { name: 'Filters' });
    expect(pill).toHaveAttribute('aria-expanded', 'false');
    await userEvent.click(pill);
    expect(pill).toHaveAttribute('aria-expanded', 'true');
  });
});
