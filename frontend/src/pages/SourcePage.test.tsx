import { screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { SourcePage } from './SourcePage';
import { server, work, taxGroupHandler, emptyPage } from '../test/server';
import { renderApp } from '../test/render';

function renderSource() {
  renderApp(<Routes><Route path="/source/:id" element={<SourcePage />} /></Routes>, '/source/7');
}

describe('SourcePage', () => {
  beforeEach(() => server.use(http.get('/api/works', () => HttpResponse.json(emptyPage))));

  it('sourcePageShowsCategorisationAndRecentWorks', async () => {
    server.use(
      taxGroupHandler,
      http.get('/api/sources/7', () => HttpResponse.json({
        id: 7, title: 'Zootaxa', publisher: 'Magnolia Press', tier: 'CORE', journalType: 'TAXONOMIC',
        primarySubfield: '1109', subfields: ['1109', '1105', '4242'],
        taxgroups: ['Insects', 'Animals'], taxgroupShares: { Animals: 0.12, Insects: 0.62 },
        codes: ['ZOOLOGICAL'], oaModel: 'HYBRID', country: 'NZ', nameDensity: 4.2371, actRate: 0.31, worksPerYear: 1500,
        years: [{ year: 2023, works: 1400 }, { year: 2024, works: 1500 }],
        recentWorks: [work(1, 'A recent paper')],
      })),
    );
    renderSource();
    expect(await screen.findByRole('heading', { name: 'Zootaxa' })).toBeInTheDocument();
    expect(screen.getByText(/Magnolia Press/)).toBeInTheDocument();
    // no all-caps labels: API values in sentence case
    expect(screen.getByText('Core')).toBeInTheDocument();
    const groups = screen.getByRole('region', { name: 'Taxonomic groups' });
    expect(await within(groups).findByText('62%')).toBeInTheDocument();
    expect(within(groups).getByText('12%')).toBeInTheDocument();
    // the icon is decoration next to the visible name
    await waitFor(() => expect(groups.querySelector('img[src="https://icons.example/insects.svg"]')).toHaveAttribute('alt', ''));
    expect(within(groups).queryByRole('img')).toBeNull();
    const cat = screen.getByRole('region', { name: 'Categorisation' });
    expect(within(cat).getByText('Zoological')).toBeInTheDocument();
    expect(within(cat).getByText('Taxonomic')).toBeInTheDocument();
    expect(within(cat).getByText('4.2')).toBeInTheDocument();
    // the subfields under a visible label, the primary one said in words, not only set in 500
    expect(within(cat).getByText('Disciplines')).toBeVisible();
    const [insects, eco, other] = within(within(cat).getByRole('list', { name: 'Disciplines' })).getAllByRole('listitem');
    expect(insects).toHaveTextContent(/^Insect Science \(primary\)$/);
    expect(insects).toHaveAttribute('title', '1109');
    expect(eco).toHaveTextContent(/^Ecology, Evolution, Behavior and Systematics$/);
    expect(other).toHaveTextContent(/^4242$/);
    expect(within(cat).getByText('Hybrid')).toBeInTheDocument();
    expect(within(cat).getByText('NZ')).toBeInTheDocument();
    const perYear = screen.getByRole('region', { name: 'Papers per year' });
    expect(within(perYear).getByText('2024')).toBeInTheDocument();
    const recent = screen.getByRole('region', { name: 'Recent papers' });
    expect(within(recent).getByText('A recent paper')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /All papers/ })).toHaveAttribute('href', '/?source=7');
    expect(document.title).toBe('Garrulus — Zootaxa');
  });

  it('offers follow and feed only for sources the feeds serve', async () => {
    const detail = (tier: string) => ({ id: 7, title: 'Zootaxa', tier, subfields: [], taxgroups: [], codes: [], years: [], recentWorks: [] });
    server.use(taxGroupHandler, http.get('/api/sources/7', () => HttpResponse.json(detail('CORE'))));
    const core = renderApp(<Routes><Route path="/source/:id" element={<SourcePage />} /></Routes>, '/source/7');
    expect(await screen.findByRole('heading', { name: 'Zootaxa' })).toBeInTheDocument();
    const follow = await screen.findByRole('link', { name: 'Follow' });
    const feed = screen.getByRole('link', { name: 'Atom feed' });
    expect(feed).toHaveAttribute('href', '/feeds/source/7.atom');
    // the page actions sit in the first row with the title, as on every page, not at the top of the side column
    const head = screen.getByRole('heading', { name: 'Zootaxa' }).closest('div')!.parentElement!;
    expect(head).toContainElement(follow);
    expect(head).toContainElement(feed);
    expect(document.querySelector('aside')).not.toContainElement(follow);
    core.unmount();

    server.use(http.get('/api/sources/7', () => HttpResponse.json(detail('CANDIDATE'))));
    renderSource();
    expect(await screen.findByRole('heading', { name: 'Zootaxa' })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Follow' })).toBeNull();
    expect(screen.queryByRole('button', { name: /Follow/ })).toBeNull();
    expect(document.querySelector('a[href="/feeds/source/7.atom"]')).toBeNull();
  });

  it('unknownSourceIsFriendly', async () => {
    server.use(taxGroupHandler, http.get('/api/sources/7', () => HttpResponse.json({ error: 'nope' }, { status: 404 })));
    renderSource();
    expect(await screen.findByRole('heading', { level: 1, name: 'Journal not found' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Go to the search' })).toHaveAttribute('href', '/');
  });

  it('showsCeasedBadgeAndSuccessor', async () => {
    server.use(taxGroupHandler, http.get('/api/sources/7', () => HttpResponse.json({
      id: 7, title: 'Old Journal', tier: 'CORE', publicationStatus: 'CEASED', lastIssueYear: 2015,
      continuedBy: { id: 9, title: 'New Journal' }, subfields: [], taxgroups: [], codes: [], years: [], recentWorks: [],
    })));
    renderSource();
    expect(await screen.findByText(/Ceased publication · last issue 2015/)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'New Journal' })).toHaveAttribute('href', '/source/9');
  });

  it('saysWhenTheDisciplineVocabularyIsMissing', async () => {
    server.use(taxGroupHandler,
      http.get('/api/vocab/discipline', () => new HttpResponse(null, { status: 500 })),
      http.get('/api/sources/7', () => HttpResponse.json({ id: 7, title: 'Zootaxa', tier: 'CORE', subfields: ['1109'], taxgroups: [], codes: [], years: [], recentWorks: [] })));
    renderSource();
    expect(await screen.findByText('1109')).toBeInTheDocument();
    expect(await screen.findByText('Discipline names are not available now; codes are shown.')).toBeInTheDocument();
  });

  it('journalIdentifiersAndLanguages', async () => {
    server.use(taxGroupHandler, http.get('/api/sources/7', () => HttpResponse.json({
      id: 7, title: 'Zootaxa', tier: 'CORE', subfields: [], taxgroups: [], codes: [], years: [], recentWorks: [],
      identifiers: [{ scheme: 'ISSN', label: 'ISSN', value: '1175-5326', url: 'https://portal.issn.org/resource/ISSN/1175-5326' }],
      languages: [{ language: 'en', year: 2025, works: 9 }, { language: 'zh', year: 2025, works: 2 }],
    })));
    renderSource();
    expect(await screen.findByRole('link', { name: 'ISSN 1175-5326' })).toHaveAttribute('href', 'https://portal.issn.org/resource/ISSN/1175-5326');
    const langs = screen.getByRole('region', { name: 'Languages' });
    expect(within(langs).getByRole('rowheader', { name: 'Chinese' })).toBeInTheDocument();
    expect(within(langs).getByRole('rowheader', { name: 'English' })).toBeInTheDocument();
  });

  it('languageTableSetsTheFilter', async () => {
    const seen: URLSearchParams[] = [];
    server.use(taxGroupHandler,
      http.get('/api/sources/7', () => HttpResponse.json({
        id: 7, title: 'Zootaxa', tier: 'CORE', subfields: [], taxgroups: [], codes: [], years: [], recentWorks: [work(1, 'Recent one')],
        languages: [{ language: 'en', year: 2025, works: 9 }, { language: '', year: 2025, works: 2 }],
      })),
      http.get('/api/works', ({ request }) => {
        seen.push(new URL(request.url).searchParams);
        return HttpResponse.json({ ...emptyPage, total: 1, results: [work(2, 'Filtered one')] });
      }));
    renderSource();
    const langs = await screen.findByRole('region', { name: 'Languages' });
    await userEvent.click(within(langs).getByRole('button', { name: 'Undecided' }));
    expect(await screen.findByText('Filtered one')).toBeInTheDocument();
    expect(seen[seen.length - 1].getAll('language')).toEqual(['none']);
    expect(within(langs).getByRole('button', { name: 'Undecided' })).toHaveAttribute('aria-pressed', 'true');
    // a second click clears it: the journal's own recent papers again
    await userEvent.click(within(langs).getByRole('button', { name: 'Undecided' }));
    expect(await screen.findByText('Recent one')).toBeInTheDocument();
  });

  it('filtersTheJournalListByPartAndLanguage', async () => {
    const seen: URLSearchParams[] = [];
    server.use(
      taxGroupHandler,
      http.get('/api/sources/7', () => HttpResponse.json({
        id: 7, title: 'Zootaxa', tier: 'CORE', subfields: [], taxgroups: [], codes: [], years: [], recentWorks: [work(1, 'Recent one')] })),
      http.get('/api/works', ({ request }) => {
        const q = new URL(request.url).searchParams;
        seen.push(q);
        return HttpResponse.json({ ...emptyPage, total: 1, results: [work(2, 'Filtered one')],
          facets: { language: [{ value: 'de', count: 1 }] } });
      }),
    );
    renderApp(<Routes><Route path="/source/:id" element={<SourcePage />} /></Routes>, '/source/7?mentionedIn=ABSTRACT&language=de');
    expect(await screen.findByText('Filtered one')).toBeInTheDocument();
    expect(screen.queryByText('Recent one')).toBeNull();
    const q = seen[seen.length - 1];
    expect(q.get('source')).toBe('7');
    expect(q.getAll('mentionedIn')).toEqual(['ABSTRACT']);
    expect(q.getAll('language')).toEqual(['de']);
    expect(screen.getByLabelText('Abstract')).toBeChecked();
    // the selected language has no row in the journal's language table: it can still be cleared there
    expect(screen.getByRole('button', { name: 'Show papers in all languages' })).toBeInTheDocument();
    // feeds and follows do not filter by language: hidden while a language is selected
    expect(screen.queryByRole('link', { name: 'Atom feed' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Follow' })).toBeNull();
    expect(screen.getByText('Feeds and follows do not filter by language.')).toBeInTheDocument();
  });

  it('keepsFeedWithoutLanguageFilter', async () => {
    server.use(taxGroupHandler, http.get('/api/sources/7', () => HttpResponse.json({
      id: 7, title: 'Zootaxa', tier: 'CORE', subfields: [], taxgroups: [], codes: [], years: [], recentWorks: [] })));
    renderApp(<Routes><Route path="/source/:id" element={<SourcePage />} /></Routes>, '/source/7?mentionedIn=ABSTRACT');
    expect(await screen.findByRole('link', { name: 'Atom feed' })).toHaveAttribute('href', '/feeds/source/7.atom?mentionedIn=ABSTRACT');
    expect(screen.queryByText('Feeds and follows do not filter by language.')).toBeNull();
  });
});
