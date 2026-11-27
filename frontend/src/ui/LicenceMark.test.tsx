import { render, screen } from '@testing-library/react';
import { LicenceMark, ccIcons } from './LicenceMark';

describe('LicenceMark', () => {
  it('iconsOfCcCodes', () => {
    expect(ccIcons('cc-by-nc-sa')).toEqual(['cc', 'by', 'nc', 'sa']);
    expect(ccIcons('CC-BY-4.0')).toEqual(['cc', 'by']);
    expect(ccIcons('cc0')).toEqual(['cc', 'zero']);
    expect(ccIcons('public-domain')).toEqual(['pd']);
    expect(ccIcons('publisher-specific-oa')).toBeNull();
  });
  it('ccAsIconsOtherAsText', () => {
    const { container, unmount } = render(<LicenceMark licence="cc-by-nd" />);
    expect(screen.getByRole('img', { name: 'CC BY-ND' })).toBeInTheDocument();
    expect(container.querySelectorAll('svg')).toHaveLength(3);
    unmount();
    render(<LicenceMark licence="other-oa" />);
    expect(screen.getByText('Other open licence')).toBeInTheDocument();
  });
});
