import { Button } from '@mantine/core';
import { Link, Route, Routes } from 'react-router-dom';
import { EmptyState } from './ui/EmptyState';
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
      <Route path="*" element={
        <EmptyState title="Page not found" action={<Button component={Link} to="/">Go to the search</Button>}>
          There is nothing at this address.
        </EmptyState>
      } />
    </Routes>
  );
}
