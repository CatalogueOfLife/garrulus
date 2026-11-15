import { screen } from '@testing-library/react';
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
});
