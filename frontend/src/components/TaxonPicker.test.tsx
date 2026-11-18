import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { TaxonPicker } from './TaxonPicker';
import { renderApp } from '../test/render';
import { server, xrHandler } from '../test/server';

describe('TaxonPicker', () => {
  it('taxonPickerUsesPinnedXrKey', async () => {
    const urls: string[] = [];
    server.use(
      xrHandler,
      http.get('https://api.checklistbank.org/dataset/:key/nameusage/suggest', ({ request, params }) => {
        urls.push(`${params.key}|${new URL(request.url).searchParams.get('q')}`);
        return HttpResponse.json([
          { match: 'Carabidae Latreille, 1802', usageId: '8KTL7', rank: 'family', suggestion: 'Carabidae Latreille, 1802 (family in Adephaga)' },
        ]);
      }),
    );
    const picked: string[] = [];
    renderApp(<TaxonPicker onPick={(id) => picked.push(id)} onClear={() => {}} />);
    await userEvent.type(screen.getByLabelText('Taxon'), 'Carab');
    const option = await screen.findByText(/Carabidae Latreille, 1802 \(family/);
    expect(urls.at(-1)).toBe('316691|Carab');
    await userEvent.click(option);
    expect(picked).toEqual(['8KTL7']);
  });

  it('keepsTheFocusInThePickerWhenASuggestionIsChosenByKeyboard', async () => {
    server.use(
      xrHandler,
      http.get('https://api.checklistbank.org/dataset/:key/nameusage/suggest', () => HttpResponse.json([
        { match: 'Carabidae Latreille, 1802', usageId: '8KTL7', rank: 'family', suggestion: 'Carabidae Latreille, 1802' },
      ])),
    );
    const user = userEvent.setup();
    renderApp(<TaxonPicker onPick={() => {}} onClear={() => {}} />);
    await user.type(screen.getByLabelText('Taxon'), 'Carab');
    await screen.findByRole('option');
    await user.tab();
    expect(screen.getByRole('option')).toHaveFocus();
    await user.keyboard('{Enter}');
    // the chosen option disappears with the list; focus goes back to the field, not to the top of the page
    await waitFor(() => expect(screen.queryByRole('option')).toBeNull());
    expect(screen.getByLabelText('Taxon')).toHaveFocus();
  });

  it('keepsTheFocusInThePickerWhenTheTaxonIsCleared', async () => {
    server.use(xrHandler);
    const cleared: boolean[] = [];
    const user = userEvent.setup();
    renderApp(<TaxonPicker label="Carabidae" onPick={() => {}} onClear={() => cleared.push(true)} />);
    await user.click(screen.getByRole('button', { name: 'Clear taxon' }));
    expect(cleared).toEqual([true]);
    expect(screen.getByLabelText('Taxon')).toHaveFocus();
  });

  it('italicises only the name of a species suggestion, not the authorship', async () => {
    server.use(
      xrHandler,
      http.get('https://api.checklistbank.org/dataset/:key/nameusage/suggest', () => HttpResponse.json([
        { match: 'Abies alba Mill.', usageId: 'X1', rank: 'species', suggestion: 'Abies alba Mill.' },
      ])),
    );
    renderApp(<TaxonPicker onPick={() => {}} onClear={() => {}} />);
    await userEvent.type(screen.getByLabelText('Taxon'), 'Abies');
    const italic = await screen.findByText('Abies alba');
    expect(italic.tagName).toBe('I');
    expect(italic.parentElement).toHaveTextContent('Abies alba Mill.');
    expect(italic).not.toHaveTextContent('Mill.');
  });

  it('sets rank markers roman inside the italic name of a suggestion', async () => {
    server.use(
      xrHandler,
      http.get('https://api.checklistbank.org/dataset/:key/nameusage/suggest', () => HttpResponse.json([
        { match: 'Fusarium oxysporum f. sp. lycopersici', usageId: 'X2', rank: 'forma_specialis',
          suggestion: 'Fusarium oxysporum f. sp. lycopersici W.C. Snyder & H.N. Hansen' },
      ])),
    );
    renderApp(<TaxonPicker onPick={() => {}} onClear={() => {}} />);
    await userEvent.type(screen.getByLabelText('Taxon'), 'Fusarium');
    const option = await screen.findByRole('option');
    expect(option.innerHTML).toBe('<i>Fusarium oxysporum</i> f. sp. <i>lycopersici</i> W.C. Snyder &amp; H.N. Hansen');
  });
});
