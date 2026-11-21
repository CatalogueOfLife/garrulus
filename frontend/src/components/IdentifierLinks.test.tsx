import { render, screen } from '@testing-library/react';
import { IdentifierLinks } from './IdentifierLinks';

describe('IdentifierLinks', () => {
  it('rendersLabelsAndUrls', () => {
    render(<IdentifierLinks identifiers={[{ scheme: 'PMID', label: 'PubMed', value: '123', url: 'https://europepmc.org/article/MED/123' }]} />);
    const a = screen.getByRole('link', { name: 'PubMed 123' });
    expect(a).toHaveAttribute('href', 'https://europepmc.org/article/MED/123');
    expect(a).toHaveAttribute('target', '_blank');
    expect(a).toHaveAttribute('rel', expect.stringContaining('noopener'));
    expect(a.getAttribute('rel')).toContain('noreferrer');
  });
  it('textWithoutUrl', () => {
    render(<IdentifierLinks identifiers={[{ scheme: 'BPH', label: 'BPH', value: '42', url: null }]} />);
    expect(screen.getByText('BPH 42')).toBeInTheDocument();
    expect(screen.queryByRole('link')).toBeNull();
  });
  it('unsafeUrlIsPlainText', () => {
    render(<IdentifierLinks identifiers={[{ scheme: 'X', label: 'X', value: '1', url: 'javascript:alert(1)' }]} />);
    expect(screen.queryByRole('link')).toBeNull();
    expect(screen.getByText('X 1')).toBeInTheDocument();
  });
});
