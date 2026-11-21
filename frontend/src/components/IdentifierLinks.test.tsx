import { render, screen } from '@testing-library/react';
import { IdentifierLinks } from './IdentifierLinks';

describe('IdentifierLinks', () => {
  it('rendersLabelsAndUrls', () => {
    render(<IdentifierLinks identifiers={[{ scheme: 'PMID', label: 'PubMed', value: '123', url: 'https://europepmc.org/article/MED/123' }]} />);
    const a = screen.getByRole('link', { name: 'PubMed 123' });
    expect(a).toHaveAttribute('href', 'https://europepmc.org/article/MED/123');
    expect(a).toHaveAttribute('target', '_blank');
    expect(a).toHaveAttribute('rel', expect.stringContaining('noopener'));
  });
  it('textWithoutUrl', () => {
    render(<IdentifierLinks identifiers={[{ scheme: 'BPH', label: 'BPH', value: '42', url: null }]} />);
    expect(screen.getByText('BPH 42')).toBeInTheDocument();
    expect(screen.queryByRole('link')).toBeNull();
  });
});
