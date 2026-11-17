import { useLocation } from 'react-router-dom';
import { useTimeZoneProposal } from './auth/useTimeZoneProposal';
import { AppRoutes } from './routes';
import { Footer } from './ui/Footer';
import { PageHeader } from './ui/PageHeader';
import styles from './ui/frame.module.css';

/** The big header (with the display wordmark) belongs to the search page `/`, which renders it itself. */
const hasCompactHeader = (pathname: string) => pathname !== '/';

export function App() {
  useTimeZoneProposal();
  const { pathname } = useLocation();
  return (
    <>
      {hasCompactHeader(pathname) && <PageHeader variant="compact" />}
      <main className={hasCompactHeader(pathname) ? styles.main : undefined}>
        <AppRoutes />
      </main>
      <Footer />
    </>
  );
}
