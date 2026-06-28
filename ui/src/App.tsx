import { Routes, Route, Navigate, useLocation } from 'react-router-dom';
import { AppShell } from './components/layout/AppShell';
import { DocumentsPage } from './pages/DocumentsPage';
import { DocumentDetailPage } from './pages/DocumentDetailPage';
import { RetrievalPage } from './pages/RetrievalPage';
import { ChatPage } from './pages/ChatPage';
import { PlaceholderPage } from './pages/PlaceholderPage';
import { AdminLayout } from './components/admin/AdminLayout';
import { ProtectedRoute } from './components/admin/ProtectedRoute';
import { AdminLoginPage } from './pages/admin/AdminLoginPage';
import { AdminDashboardPage } from './pages/admin/AdminDashboardPage';
import {
  AdminDocumentsPage,
  AdminDocumentDetailPage,
} from './pages/admin/AdminDocumentsPage';
import { AdminKnowledgeRoutePage } from './pages/admin/AdminKnowledgeRoutePage';
import { AdminKnowledgeRouteTracePage } from './pages/admin/AdminKnowledgeRouteTracePage';
import { AdminObservabilityListPage } from './pages/admin/AdminObservabilityListPage';
import { AdminObservabilitySessionPage } from './pages/admin/AdminObservabilitySessionPage';
import { AdminObservabilityExchangePage } from './pages/admin/AdminObservabilityExchangePage';

export function App() {
  const location = useLocation();
  // 阶段：Chat 自带全屏双栏布局，脱离 AppShell 的内边距容器
  const isChat = location.pathname.startsWith('/chat') || location.pathname.startsWith('/conversations');
  // 阶段：Admin 区域使用独立 Shell，全屏脱离 AppShell
  const isAdmin = location.pathname.startsWith('/admin');

  if (isAdmin) {
    return (
      <Routes>
        <Route path="/admin/login" element={<AdminLoginPage />} />
        <Route
          path="/admin"
          element={
            <ProtectedRoute>
              <AdminLayout />
            </ProtectedRoute>
          }
        >
          <Route index element={<Navigate to="/admin/dashboard" replace />} />
          <Route path="dashboard" element={<AdminDashboardPage />} />
          <Route path="documents" element={<AdminDocumentsPage />} />
          <Route path="documents/:id" element={<AdminDocumentDetailPage />} />
          <Route path="knowledge-route" element={<AdminKnowledgeRoutePage />} />
          <Route path="knowledge-route/traces" element={<AdminKnowledgeRouteTracePage />} />
          <Route path="observability" element={<AdminObservabilityListPage />} />
          <Route path="observability/:conversationId" element={<AdminObservabilitySessionPage />} />
          <Route
            path="observability/:conversationId/exchanges/:exchangeId"
            element={<AdminObservabilityExchangePage />}
          />
        </Route>
        <Route path="/admin/*" element={<Navigate to="/admin/dashboard" replace />} />
      </Routes>
    );
  }

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
