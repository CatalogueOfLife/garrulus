import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MantineProvider } from '@mantine/core';
import { MentionedInFilter, withMentionedIn } from './MentionedInFilter';

const wrap = (ui: React.ReactElement) => render(<MantineProvider>{ui}</MantineProvider>);

describe('MentionedInFilter', () => {
  it('togglesValues', async () => {
    const seen: string[][] = [];
    wrap(<MentionedInFilter value={['TITLE']} onChange={(v) => seen.push(v)} />);
    expect(screen.getByLabelText('Title')).toBeChecked();
    expect(screen.getByLabelText('Abstract')).not.toBeChecked();
    await userEvent.click(screen.getByLabelText('Full text'));
    await userEvent.click(screen.getByLabelText('Title'));
    expect(seen).toEqual([['TITLE', 'BODY'], []]);
  });

  it('hintShown', () => {
    wrap(<MentionedInFilter value={[]} onChange={() => {}} />);
    expect(screen.getByText('Full text is available for some papers only.')).toBeInTheDocument();
  });

  it('feedLinkCarriesTheParts', () => {
    expect(withMentionedIn('/feeds/taxon/T1.atom', ['TITLE', 'BODY'])).toBe('/feeds/taxon/T1.atom?mentionedIn=TITLE&mentionedIn=BODY');
    expect(withMentionedIn('/feeds/taxon/T1.atom', [])).toBe('/feeds/taxon/T1.atom');
  });
});
