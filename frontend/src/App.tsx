import { useLocation } from 'react-router-dom';
import { useTimeZoneProposal } from './auth/useTimeZoneProposal';
import { AppRoutes } from './routes';
import { Footer } from './ui/Footer';
import { PageHeader } from './ui/PageHeader';
import styles from './ui/frame.module.css';

/** The big header (with the display wordmark) belongs to the search page `/` only. */
const headerVariant = (pathname: string) => (pathname === '/' ? 'big' : 'compact');

export function App() {
  useTimeZoneProposal();
  const { pathname } = useLocation();
  return (
    <>
      <PageHeader variant={headerVariant(pathname)} />
      <main className={styles.main}>
        <AppRoutes />
      </main>
      <Footer />
    </>
  );
}
