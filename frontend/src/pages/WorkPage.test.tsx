import { screen, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { Route, Routes } from 'react-router-dom';
import { WorkPage } from './WorkPage';
import { server } from '../test/server';
import { renderApp } from '../test/render';
import type { MentionSpan, NameDetail, WorkDetail } from '../api';

const nm = (id: number, name: string, over: Partial<NameDetail> = {}): NameDetail => ({
  id, name, kind: 'SCIENTIFIC', placementMethod: 'MATCH', usageId: null, usageName: null, salience: 0.5,
  mainSubject: false, onlyReference: false, mentionCount: 1, actTypes: [], bestSnippet: '', classification: [], ...over,
});

const base: WorkDetail = {
  id: 5, title: 'Carabid notes', year: 2026, authors: [{ name: 'A. Author' }], retained: false, hasActs: false,
  codes: [], taxgroups: [], contexts: [], text: null,
  names: [
    nm(1, 'Carabus auratus', { usageId: 'T1', usageName: 'Carabus auratus L.', classification: [{ id: 'G', name: 'Carabus', rank: 'genus' }, { id: 'F', name: 'Carabidae', rank: 'family' }], bestSnippet: 'SNIPPET-ONE' }),
    nm(2, 'Carabus novus', { placementMethod: 'GENUS', usageId: 'G', usageName: 'Carabus', classification: [{ id: 'F', name: 'Carabidae', rank: 'family' }] }),
    nm(3, 'Foo bar', { placementMethod: 'VERBATIM_CONTEXT', usageId: 'T9', usageName: 'Foidae' }),
    nm(4, 'Lost name', { placementMethod: 'NONE' }),
  ],
};

function mount(w: WorkDetail) {
  server.use(http.get('/api/works/5', () => HttpResponse.json(w)));
  renderApp(<Routes><Route path="/work/:id" element={<WorkPage />} /></Routes>, '/work/5');
}

describe('WorkPage', () => {
  it('workPagePlacesNamesInOneColTree', async () => {
    mount(base);
    const col = await screen.findByRole('region', { name: 'Names in COL' });
    // the shared root on one line, the taxon of a matched name is that name's own row, a genus placement hangs below
    const genus = within(col).getByRole('link', { name: 'Carabus' });
    expect(genus).toHaveAttribute('href', '/taxon/G');
    expect(genus.closest('li')!.firstElementChild!.textContent).toBe('Carabidae › Carabus');
    const below = within(genus.closest('li')!.querySelector('ul')!);
    expect(below.getByRole('link', { name: 'Carabus auratus' })).toHaveAttribute('href', '/name/1');
    expect(below.getByRole('link', { name: 'Carabus novus' })).toHaveAttribute('href', '/name/2');
    expect(col.querySelector('a[href="/taxon/T1"]')).toBeNull();
    // a verbatim placement is in the same tree, labelled
    expect(within(col).getByRole('link', { name: 'Foo bar' })).toHaveAttribute('href', '/name/3');
    expect(within(col).getByRole('link', { name: 'Foidae' })).toHaveAttribute('href', '/taxon/T9');
    expect(within(col).getByText('(by the verbatim classification)')).toBeInTheDocument();
    const none = screen.getByRole('region', { name: 'Not placed' });
    expect(within(none).getByRole('link', { name: 'Lost name' })).toHaveAttribute('href', '/name/4');
    expect(document.title).toBe('Garrulus — Carabid notes');
  });

  it('showsActTypesAsLabels', async () => {
    mount({ ...base, names: [nm(1, 'Carabus auratus', { actTypes: ['SP_NOV', 'COMB_NOV'] })] });
    expect(await screen.findByText('sp. nov.')).toBeInTheDocument();
    expect(screen.getByText('comb. nov.')).toBeInTheDocument();
    expect(screen.queryByText('SP_NOV')).toBeNull();
  });

  it('titleItalicisesMentionsForAWorkWithoutText', async () => {
    mount({ ...base, title: 'Notes on Carabus auratus and Carabidae', titleMentions: [
      { id: 1, nameId: 1, startCp: 9, endCp: 24, verbatim: 'Carabus auratus' },
      { id: 2, nameId: 5, startCp: 29, endCp: 38, verbatim: 'Carabidae' },
    ], names: [nm(1, 'Carabus auratus', { rank: 'species' }), nm(5, 'Carabidae', { rank: 'family' })] });
    const h = await screen.findByRole('heading', { level: 1 });
    expect(h.textContent).toBe('Notes on Carabus auratus and Carabidae');
    expect(h.querySelector('i')!.textContent).toBe('Carabus auratus');
    expect(h.querySelectorAll('i')).toHaveLength(1);
  });

  it('verbatimClassificationNextToColOne', async () => {
    mount({ ...base, contexts: [{ localId: 'c1', verbatim: 'Coleoptera: Carabidae: Carabus', ranks: [{ rank: 'ORDER', name: 'Coleoptera' }], scope: 'DOCUMENT', blockId: 'b1' }] });
    const verbatim = await screen.findByRole('region', { name: 'Verbatim classification in the article' });
    expect(within(verbatim).getByText('Coleoptera: Carabidae: Carabus')).toBeInTheDocument();
    const col = screen.getByRole('region', { name: 'Names in COL' });
    expect(within(col).getByTestId('name-tree').querySelector('li > span')!.textContent).toBe('Carabidae › Carabus'); // root first
  });

  it('closedWorkShowsNoTextOnlyBodySnippetsWithTheNameLinked', async () => {
    mount({ ...base, snippets: [{ nameId: 1, blockType: 'PARAGRAPH', text: 'We found Carabus auratus here.',
      mentions: [{ id: 9, nameId: 1, startCp: 9, endCp: 24, verbatim: 'Carabus auratus' }] }] });
    const snippets = await screen.findByRole('region', { name: 'Snippets' });
    expect(snippets.textContent).toContain('We found Carabus auratus here.');
    expect(within(snippets).getByRole('link', { name: 'Carabus auratus' })).toHaveAttribute('href', '/name/1');
    expect(screen.queryByRole('region', { name: 'Full text' })).toBeNull();
    // the name's best snippet (often the title) is not a body snippet
    expect(screen.queryByText(/SNIPPET-ONE/)).toBeNull();
  });

  it('showsNoSnippetsForAWorkWithoutBodySnippets', async () => {
    mount({ ...base, abstract: 'An abstract.', snippets: [] });
    expect(await screen.findByText('An abstract.')).toBeInTheDocument();
    expect(screen.queryByRole('region', { name: 'Snippets' })).toBeNull();
  });

  it('marksAndLinksTheNamesInTheAbstractParagraphs', async () => {
    mount({ ...base, abstract: 'Carabus auratus is common.\n\nIn 😀 Carabidae too.', abstractMentions: [
      { id: 1, nameId: 1, startCp: 0, endCp: 15, verbatim: 'Carabus auratus' },
      { id: 2, nameId: 5, startCp: 33, endCp: 42, verbatim: 'Carabidae', actType: 'FAM_NOV' },
    ], names: [nm(1, 'Carabus auratus', { rank: 'species' }), nm(5, 'Carabidae', { rank: 'family' })] });
    const lede = (await screen.findByText(/is common\./)).closest('p')!;
    const species = within(lede).getByRole('link', { name: 'Carabus auratus' });
    expect(species).toHaveAttribute('href', '/name/1');
    expect(species.querySelector('i')).not.toBeNull();
    const second = screen.getByText(/too\./).closest('p')!;
    expect(second).not.toBe(lede);
    const family = within(second).getByRole('link', { name: 'Carabidae' });
    expect(family).toHaveAttribute('href', '/name/5');
    expect(family.querySelector('i')).toBeNull();
    expect(family.className).toContain('gr-mark-act');
  });

  it('setsTheActMarkersOfTheAbstractInRed', async () => {
    mount({ ...base, abstract: 'Intro.\n\nCarabus novus sp. nov. is described.', abstractMentions: [
      { id: 1, nameId: 2, startCp: 8, endCp: 21, verbatim: 'Carabus novus', actType: 'SP_NOV' },
    ], abstractActs: [{ mentionId: 1, startCp: 22, endCp: 30, actType: 'SP_NOV' }] });
    const marker = await screen.findByText('sp. nov.');
    expect(marker).toHaveClass('gr-act-mark');
    expect(marker.closest('p')!.textContent).toBe('Carabus novus sp. nov. is described.');
  });

  it('openWorkShowsHighlightedText', async () => {
    mount({ ...base, retained: true, licence: 'CC-BY-4.0', text: [{
      id: 'b1', type: 'paragraph', level: 0, sectionPath: [], text: '😀 Carabus auratus.',
      mentions: [{ id: 1, nameId: 1, startCp: 2, endCp: 17, verbatim: 'Carabus auratus' }],
    }] });
    const text = await screen.findByRole('region', { name: 'Full text' });
    expect(within(text).getByRole('link', { name: 'Carabus auratus' })).toHaveAttribute('href', '/name/1');
    expect(screen.queryByText(/SNIPPET-ONE/)).toBeNull();
  });

  it('runsConsecutiveTableCellsTogetherInOneBlock', async () => {
    const block = (id: string, type: string, text: string, mentions: MentionSpan[] = []) =>
      ({ id, type, level: 0, sectionPath: [], text, mentions });
    mount({ ...base, retained: true, licence: 'CC-BY-4.0', text: [
      block('p1', 'PARAGRAPH', 'Before the table.'),
      block('c1', 'TABLE_CELL', 'Species'),
      block('c2', 'TABLE_CELL', 'Carabus auratus', [{ id: 7, nameId: 1, startCp: 0, endCp: 15, verbatim: 'Carabus auratus' }]),
      block('c3', 'TABLE_CELL', '12 mm'),
      block('p2', 'PARAGRAPH', 'After the table.'),
    ] });
    const text = await screen.findByRole('region', { name: 'Full text' });
    // one element holds the run of cells (each cell its own span); the paragraphs around it stay apart
    const run = within(text).getByText('Species').closest('p')!;
    expect(run).toContainElement(within(text).getByRole('link', { name: 'Carabus auratus' }));
    expect(run).toContainElement(within(text).getByText('12 mm'));
    expect(run).not.toContainElement(within(text).getByText('Before the table.'));
    expect(run).not.toContainElement(within(text).getByText('After the table.'));
  });

  it('setsHeadingBlocksAsHeadingsWhateverTheCaseOfTheirType', async () => {
    // the API sends block types in upper case (HEADING, PARAGRAPH, TITLE)
    const block = (id: string, type: string, text: string) => ({ id, type, level: 0, sectionPath: [], text, mentions: [] });
    mount({ ...base, retained: true, licence: 'CC-BY-4.0', text: [
      block('b1', 'HEADING', 'Introduction'), block('b2', 'PARAGRAPH', 'Body text.'),
    ] });
    const text = await screen.findByRole('region', { name: 'Full text' });
    expect(within(text).getByRole('heading', { level: 3, name: 'Introduction' })).toBeInTheDocument();
    expect(within(text).queryByRole('heading', { name: 'Body text.' })).toBeNull();
  });

  it('unknownWorkShowsTheNotFoundState', async () => {
    server.use(http.get('/api/works/5', () => HttpResponse.json({ error: 'nope' }, { status: 404 })));
    renderApp(<Routes><Route path="/work/:id" element={<WorkPage />} /></Routes>, '/work/5');
    expect(await screen.findByRole('heading', { level: 1, name: 'Paper not found' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Go to the search' })).toHaveAttribute('href', '/');
  });

  it('otherErrorsStayAnAlert', async () => {
    server.use(http.get('/api/works/5', () => HttpResponse.json({ error: 'database down' }, { status: 500 })));
    renderApp(<Routes><Route path="/work/:id" element={<WorkPage />} /></Routes>, '/work/5');
    expect(await screen.findByText('database down')).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Paper not found' })).toBeNull();
  });

  it('machineTitleLabelled', async () => {
    mount({ ...base, title: '中国甲虫', language: 'zh', titleLang: 'zh', titles: [
      { lang: 'zh', kind: 'ORIGINAL', text: '中国甲虫' },
      { lang: 'en', kind: 'MACHINE', text: 'Chinese beetles', model: 'libretranslate' },
    ] });
    expect(await screen.findByText('Machine translation (libretranslate):')).toBeInTheDocument();
    expect(screen.getByText('Chinese beetles')).toHaveAttribute('lang', 'en');
    // the original stays the heading
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('中国甲虫');
    expect(screen.queryByText('English title:')).toBeNull();
  });

  it('parallelTitleIsAnEnglishTitle', async () => {
    mount({ ...base, titles: [{ lang: 'en', kind: 'PARALLEL', text: 'English parallel' }], titleLang: 'de', language: 'de' });
    expect(await screen.findByText('English title:')).toBeInTheDocument();
  });

  it('langAttributes', async () => {
    mount({ ...base, title: 'Käfer', abstract: 'Ein Text.', language: 'de', titleLang: 'de', abstractLang: 'de',
      abstracts: [{ lang: 'de', kind: 'ORIGINAL', text: 'Ein Text.' }, { lang: 'fr', kind: 'PARALLEL', text: 'Un texte.' }] });
    expect(await screen.findByRole('heading', { level: 1 })).toHaveAttribute('lang', 'de');
    expect(screen.getByText('Ein Text.').closest('p')).toHaveAttribute('lang', 'de');
    expect(screen.getByText('Abstract in French')).toBeInTheDocument();
    expect(screen.getByText('Un texte.')).toHaveAttribute('lang', 'fr');
  });

  it('identifiersRow', async () => {
    mount({ ...base, identifiers: [{ scheme: 'PMID', label: 'PubMed', value: '9', url: 'https://europepmc.org/article/MED/9' }] });
    expect(await screen.findByRole('link', { name: 'PubMed 9' })).toHaveAttribute('href', 'https://europepmc.org/article/MED/9');
  });

  it('showsTheDoiOnceAmongTheIdentifiers', async () => {
    mount({ ...base, doi: '10.1/x', licence: 'cc-by', identifiers: [{ scheme: 'DOI', label: 'DOI', value: '10.1/x', url: 'https://doi.org/10.1/x' }] });
    expect(await screen.findByRole('link', { name: 'DOI 10.1/x' })).toHaveAttribute('href', 'https://doi.org/10.1/x');
    expect(screen.getAllByRole('link').filter((a) => a.getAttribute('href') === 'https://doi.org/10.1/x')).toHaveLength(1);
    const cc = screen.getByRole('img', { name: 'CC BY' }); // the CC icons, last in the citation line
    expect(cc.closest('p')!.lastElementChild!.contains(cc)).toBe(true);
  });

  it('marksMainSubjectsWithALegend', async () => {
    mount({ ...base, names: [nm(1, 'Carabus auratus', { usageId: 'T1', usageName: 'Carabus auratus', mainSubject: true }), nm(2, 'Carabus novus', { usageId: 'T2', usageName: 'Carabus novus' })] });
    const col = await screen.findByRole('region', { name: 'Names in COL' });
    expect(within(col).getByRole('link', { name: 'Carabus auratus' }).parentElement!.textContent).toContain('*');
    expect(within(col).getByRole('link', { name: 'Carabus novus' }).parentElement!.textContent).not.toContain('*');
    expect(within(col).getByText('Main subject of the paper')).toBeInTheDocument();
    expect(within(col).queryByText(/^\s*main subject$/)).toBeNull();
  });

  it('saysNotWhereANameOccurs', async () => {
    mount({ ...base, names: [nm(1, 'Carabus auratus', { usageId: 'T1', usageName: 'Carabus auratus', inTitle: true, inAbstract: true, bodyCount: 2 })] });
    const col = await screen.findByRole('region', { name: 'Names in COL' });
    expect(within(col).getByRole('link', { name: 'Carabus auratus' })).toBeInTheDocument();
    expect(within(col).queryByText(/title|abstract|full text/)).toBeNull();
  });

  it('parallelNonEnglishTitleNamed', async () => {
    mount({ ...base, titles: [{ lang: 'zh', kind: 'PARALLEL', text: '中文题' }], titleLang: 'en', language: 'en' });
    expect(await screen.findByText('Parallel title (Chinese):')).toBeInTheDocument();
  });

  it('noEmptyLangAttribute', async () => {
    mount({ ...base, titleLang: '', language: '' });
    const h = await screen.findByRole('heading', { level: 1 });
    expect(h.hasAttribute('lang')).toBe(false);
  });

  it('translateHintOncePerPage', async () => {
    mount({ ...base, title: '中文', abstract: '摘要', language: 'zh', titleLang: 'zh', abstractLang: 'zh' });
    await screen.findByRole('heading', { level: 1 });
    expect(await screen.findAllByText(/use its Translate option/)).toHaveLength(1);
  });
});
