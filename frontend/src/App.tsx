import { useLocation } from 'react-router-dom';
import { useTimeZoneProposal } from './auth/useTimeZoneProposal';
import { AppRoutes } from './routes';
import { Footer } from './ui/Footer';
import { PageHeader } from './ui/PageHeader';
import styles from './ui/frame.module.css';

export function App() {
  useTimeZoneProposal();
  const { pathname } = useLocation();
  // the search page `/` renders its big header (with the display wordmark) and then its own main landmark
  if (pathname === '/') {
    return (
      <>
        <AppRoutes />
        <Footer />
      </>
    );
  }
  return (
    <>
      <PageHeader variant="compact" />
      <main className={styles.main}>
        <AppRoutes />
      </main>
      <Footer />
    </>
  );
}
