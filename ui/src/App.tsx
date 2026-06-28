import { Routes, Route, Navigate, useLocation } from 'react-router-dom';
import { AppShell } from './components/layout/AppShell';
import { DocumentsPage } from './pages/DocumentsPage';
import { DocumentDetailPage } from './pages/DocumentDetailPage';
import { RetrievalPage } from './pages/RetrievalPage';
import { ChatPage } from './pages/ChatPage';
import { PlaceholderPage } from './pages/PlaceholderPage';

export function App() {
  const location = useLocation();
  // 阶段：Chat 自带全屏双栏布局，脱离 AppShell 的内边距容器
  const isChat = location.pathname.startsWith('/chat') || location.pathname.startsWith('/conversations');

  if (isChat) {
    return (
      <AppShell bare>
        <Routes>
          <Route path="/chat" element={<ChatPage />} />
          <Route path="/conversations" element={<Navigate to="/chat" replace />} />
        </Routes>
      </AppShell>
    );
  }

  return (
    <AppShell>
      <Routes>
        <Route path="/" element={<Navigate to="/documents" replace />} />
        <Route path="/documents" element={<DocumentsPage />} />
        <Route path="/documents/:id" element={<DocumentDetailPage />} />
        <Route path="/retrieval" element={<RetrievalPage />} />
      </Routes>
    </AppShell>
  );
}
