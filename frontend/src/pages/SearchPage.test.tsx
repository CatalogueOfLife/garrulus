import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { SearchPage } from './SearchPage';
import { activeFilterCount } from '../components/FilterSidebar';
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

  it('a lone taxonomic group puts its feed and Follow in the first row with the count, not in the side column', async () => {
    server.use(xrHandler, taxGroupHandler, sourcesHandler(), http.get('/api/works', () =>
      HttpResponse.json({ ...emptyPage, total: 1234, results: [work(1, 'Carabid revision')] })));
    renderApp(<SearchPage />, '/?taxgroup=Insects');
    const count = await screen.findByText('1,234 works');
    const feed = screen.getByRole('link', { name: 'Atom feed' });
    expect(feed).toHaveAttribute('href', '/feeds/taxgroup/Insects.atom');
    const row = count.parentElement!.parentElement!;
    expect(row).toContainElement(feed);
    expect(document.querySelector('aside')).not.toContainElement(feed);
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

  it('filtersPillCountsTheActiveFiltersAndHidesOnlyTheFacets', async () => {
    server.use(xrHandler, taxGroupHandler, sourcesHandler(1234), http.get('/api/works', () => HttpResponse.json(emptyPage)));
    renderApp(<SearchPage />, '/?q=carabus&code=ZOOLOGICAL&yearFrom=2020&yearTo=2022&offset=20');
    // the text search is not a filter, and a year range counts once
    const pill = screen.getByRole('button', { name: 'Filters (2)' });
    const panel = document.getElementById(pill.getAttribute('aria-controls')!)!;
    // the lede stays out of the panel the pill opens and closes; the facet groups are in it
    expect(panel).not.toContainElement(await screen.findByText(/Garrulus reads 1,234 journals/));
    expect(panel).toContainElement(screen.getByRole('button', { name: /Zoological/ }));
  });

  it('activeFilterCountCountsTheSidebarFilters', () => {
    expect(activeFilterCount({})).toBe(0);
    expect(activeFilterCount({ q: 'x', offset: '20', limit: '20' })).toBe(0);
    expect(activeFilterCount({ taxon: 'T1', taxgroup: 'Insects', code: 'ZOOLOGICAL', journalType: 'TAXONOMIC', hasActs: 'true',
      subfield: '1109', tier: 'CORE', oa: 'GOLD', yearFrom: '2000', yearTo: '2010' })).toBe(9);
  });

  it('offersASkipLinkPastTheFiltersAsTheFirstStopAfterTheHeader', async () => {
    server.use(xrHandler, taxGroupHandler, sourcesHandler(), http.get('/api/works', () =>
      HttpResponse.json({ ...emptyPage, total: 1, results: [work(1, 'Carabid revision')] })));
    const user = userEvent.setup();
    renderApp(<SearchPage />);
    await screen.findByText('Carabid revision');
    const skip = screen.getByRole('link', { name: 'Skip to results' });
    expect(skip).toHaveAttribute('href', '#results');
    const results = document.getElementById('results')!;
    expect(results).toHaveAttribute('tabindex', '-1');
    expect(results).toContainElement(screen.getByRole('link', { name: 'Carabid revision' }));
    // the search field is the header's last stop; the skip link comes next, before the Filters pill and the filters
    screen.getByRole('textbox', { name: 'Search text' }).focus();
    await user.tab();
    expect(skip).toHaveFocus();
    await user.keyboard('{Enter}');
    expect(results).toHaveFocus();
    await user.tab();
    expect(screen.getByRole('link', { name: 'Carabid revision' })).toHaveFocus();
  });

  it('keepsFocusInTheSearchFieldAfterEnter', async () => {
    const seen: string[] = [];
    server.use(xrHandler, taxGroupHandler, sourcesHandler(), http.get('/api/works', ({ request }) => {
      seen.push(new URL(request.url).searchParams.get('q') ?? '');
      return HttpResponse.json(emptyPage);
    }));
    renderApp(<SearchPage />, '/?q=carabus');
    const field = screen.getByRole('textbox', { name: 'Search text' });
    expect(field).toHaveValue('carabus');
    await userEvent.clear(field);
    await userEvent.type(field, 'gekko{Enter}');
    await waitFor(() => expect(seen).toContain('gekko'));
    // a keyboard user stays where they were instead of being dropped to the top of the page
    expect(screen.getByRole('textbox', { name: 'Search text' })).toHaveFocus();
    expect(screen.getByRole('textbox', { name: 'Search text' })).toHaveValue('gekko');
  });

  it('mentionedInInUrlAndRequest', async () => {
    const seen: string[] = [];
    server.use(xrHandler, taxGroupHandler, sourcesHandler(), http.get('/api/works', ({ request }) => {
      seen.push(new URL(request.url).search);
      return HttpResponse.json(emptyPage);
    }));
    renderApp(<SearchPage />, '/?mentionedIn=TITLE&mentionedIn=BODY');
    await waitFor(() => expect(seen.length).toBeGreaterThan(0));
    expect(new URLSearchParams(seen[0]).getAll('mentionedIn')).toEqual(['TITLE', 'BODY']);
    expect(screen.getByLabelText('Title')).toBeChecked();
    expect(screen.getByLabelText('Abstract')).not.toBeChecked();
    await userEvent.click(screen.getByLabelText('Abstract'));
    await waitFor(() => expect(new URLSearchParams(screen.getByTestId('location').textContent!.split('?')[1]).getAll('mentionedIn'))
      .toEqual(['TITLE', 'ABSTRACT', 'BODY']));
  });

  it('languageInRequest', async () => {
    const seen: string[] = [];
    server.use(xrHandler, taxGroupHandler, sourcesHandler(), http.get('/api/works', ({ request }) => {
      seen.push(new URL(request.url).search);
      return HttpResponse.json({ ...emptyPage, facets: { language: [{ value: 'de', count: 3 }, { value: '', count: 1 }] } });
    }));
    renderApp(<SearchPage />, '/');
    await userEvent.click(await screen.findByRole('button', { name: 'German 3' }));
    await waitFor(() => expect(screen.getByTestId('location').textContent).toContain('language=de'));
    await waitFor(() => expect(seen.some((s) => new URLSearchParams(s).getAll('language').includes('de'))).toBe(true));
    expect(new URLSearchParams(seen[0]).getAll('facet')).toContain('language');
  });

  it('machineTitleLine', async () => {
    server.use(xrHandler, taxGroupHandler, sourcesHandler(), http.get('/api/works', () => HttpResponse.json({
      ...emptyPage, total: 2, results: [
        { ...work(1, 'Neue Käferart'), language: 'de', titleLang: 'de', titleEn: 'A new beetle species', titleEnKind: 'MACHINE' },
        { ...work(2, '新种'), language: 'zh', titleLang: 'zh', titleEn: 'A new species', titleEnKind: 'PARALLEL' },
      ],
    })));
    renderApp(<SearchPage />, '/');
    const first = (await screen.findByText('Neue Käferart')).closest('article') as HTMLElement;
    expect(first.querySelector('a')).toHaveAttribute('lang', 'de');
    expect(within(first).getByText(/Machine translation:/)).toBeInTheDocument();
    expect(within(first).getByText('A new beetle species')).toHaveAttribute('lang', 'en');
    const second = screen.getByText('新种').closest('article') as HTMLElement;
    expect(within(second).getByText(/English title:/)).toBeInTheDocument();
    expect(within(second).queryByText(/Machine translation/)).toBeNull();
  });

  it('languageFilterHidesFeedAndFollow', async () => {
    server.use(xrHandler, taxGroupHandler, sourcesHandler(), http.get('/api/works', () =>
      HttpResponse.json({ ...emptyPage, total: 1, results: [work(1, 'Carabid revision')], facets: { language: [{ value: 'de', count: 1 }] } })));
    renderApp(<SearchPage />, '/?taxgroup=Insects&language=de');
    await screen.findByText('Carabid revision');
    expect(screen.queryByRole('link', { name: 'Atom feed' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Follow' })).toBeNull();
    expect(screen.queryByRole('link', { name: 'Follow' })).toBeNull();
    expect(screen.getByText('Feeds and follows do not filter by language.')).toBeInTheDocument();
  });

  it('taxgroupFeedCarriesMentionedIn', async () => {
    server.use(xrHandler, taxGroupHandler, sourcesHandler(), http.get('/api/works', () =>
      HttpResponse.json({ ...emptyPage, total: 1, results: [work(1, 'Carabid revision')] })));
    renderApp(<SearchPage />, '/?taxgroup=Insects&mentionedIn=TITLE');
    expect(await screen.findByRole('link', { name: 'Atom feed' })).toHaveAttribute('href', '/feeds/taxgroup/Insects.atom?mentionedIn=TITLE');
  });

  it('selectedLanguageWithoutBucketStaysListed', async () => {
    server.use(xrHandler, taxGroupHandler, sourcesHandler(), http.get('/api/works', () => HttpResponse.json(emptyPage)));
    renderApp(<SearchPage />, '/?language=none');
    const row = await screen.findByRole('button', { name: 'Undecided 0' });
    expect(row).toHaveAttribute('aria-current', 'true');
    await userEvent.click(row);
    await waitFor(() => expect(screen.getByTestId('location').textContent).not.toContain('language='));
  });
});
