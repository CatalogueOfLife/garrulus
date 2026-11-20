import { Navigate, Route, Routes } from 'react-router-dom';
import { NotFound } from './ui/NotFound';
import { SearchPage } from './pages/SearchPage';
import { NamePage } from './pages/NamePage';
import { SourcePage } from './pages/SourcePage';
import { TaxonPage } from './pages/TaxonPage';
import { AccountPage } from './pages/AccountPage';
import { SignInPage } from './pages/SignInPage';
import { SignInVerifyPage } from './pages/SignInVerifyPage';
import { UnsubscribePage } from './pages/UnsubscribePage';
import { VerifyEmailPage } from './pages/VerifyEmailPage';
import { WorkPage } from './pages/WorkPage';
import { FollowingPage } from './pages/FollowingPage';
import { HitsPage } from './pages/HitsPage';
import { JournalsPage } from './pages/JournalsPage';
import { AboutPage } from './pages/AboutPage';
import { DesignPage } from './pages/DesignPage';
import { AdminLayout } from './pages/admin/AdminLayout';
import { AdminJournalsPage } from './pages/admin/AdminJournalsPage';
import { AdminJournalPage } from './pages/admin/AdminJournalPage';
import { AdminLayersPage } from './pages/admin/AdminLayersPage';
import { AdminStatusPage } from './pages/admin/AdminStatusPage';

export function AppRoutes() {
  return (
    <Routes>
      <Route path="/" element={<SearchPage />} />
      <Route path="/taxon/:id" element={<TaxonPage />} />
      <Route path="/name/:id" element={<NamePage />} />
      <Route path="/work/:id" element={<WorkPage />} />
      <Route path="/source/:id" element={<SourcePage />} />
      <Route path="/signin" element={<SignInPage />} />
      <Route path="/signin/verify" element={<SignInVerifyPage />} />
      <Route path="/verify-email" element={<VerifyEmailPage />} />
      <Route path="/account" element={<AccountPage />} />
      <Route path="/unsubscribe" element={<UnsubscribePage />} />
      <Route path="/following" element={<FollowingPage />} />
      <Route path="/journals" element={<JournalsPage />} />
      <Route path="/about" element={<AboutPage />} />
      <Route path="/hits" element={<HitsPage />} />
      <Route path="/design" element={<DesignPage />} />
      <Route path="/admin" element={<AdminLayout />}>
        <Route index element={<Navigate to="/admin/journals" replace />} />
        <Route path="journals" element={<AdminJournalsPage />} />
        <Route path="journals/:id" element={<AdminJournalPage />} />
        <Route path="layers" element={<AdminLayersPage />} />
        <Route path="status" element={<AdminStatusPage />} />
      </Route>
      <Route path="*" element={<NotFound title="Page not found">There is nothing at this address.</NotFound>} />
    </Routes>
  );
}
