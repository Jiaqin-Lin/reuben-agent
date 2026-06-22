import { Routes, Route, Navigate } from 'react-router-dom';
import { AppShell } from './components/layout/AppShell';
import { DocumentsPage } from './pages/DocumentsPage';
import { DocumentDetailPage } from './pages/DocumentDetailPage';
import { RetrievalPage } from './pages/RetrievalPage';
import { PlaceholderPage } from './pages/PlaceholderPage';

export function App() {
  return (
    <AppShell>
      <Routes>
        <Route path="/" element={<Navigate to="/documents" replace />} />
        <Route path="/documents" element={<DocumentsPage />} />
        <Route path="/documents/:id" element={<DocumentDetailPage />} />
        <Route path="/retrieval" element={<RetrievalPage />} />
        <Route path="/chat" element={<PlaceholderPage label="对话" />} />
        <Route path="/conversations" element={<PlaceholderPage label="会话管理" />} />
      </Routes>
    </AppShell>
  );
}
