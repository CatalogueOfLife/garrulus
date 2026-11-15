import { Route, Routes } from 'react-router-dom';
import { SearchPage } from './pages/SearchPage';
import { NamePage } from './pages/NamePage';
import { SourcePage } from './pages/SourcePage';
import { TaxonPage } from './pages/TaxonPage';
import { WorkPage } from './pages/WorkPage';

export function AppRoutes() {
  return (
    <Routes>
      <Route path="/" element={<SearchPage />} />
      <Route path="/taxon/:id" element={<TaxonPage />} />
      <Route path="/name/:id" element={<NamePage />} />
      <Route path="/work/:id" element={<WorkPage />} />
      <Route path="/source/:id" element={<SourcePage />} />
    </Routes>
  );
}
