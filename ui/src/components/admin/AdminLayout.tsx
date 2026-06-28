import { useState, type ReactNode } from 'react';
import { NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom';
import {
  House,
  Files,
  ShareNetwork,
  Eye,
  TerminalWindow,
  ArrowSquareLeft,
  SignOut,
  List,
  X,
} from '@phosphor-icons/react';
import type { Icon } from '@phosphor-icons/react';
import { cn } from '../../lib/cn';
import { useAuth } from '../../hooks/useAuth';
import { useToast } from '../shared/Toast';
import { getAdminUser } from '../../api/client';

interface NavItem {
  to: string;
  label: string;
  icon: Icon;
  title: string;
}

const NAV_ITEMS: NavItem[] = [
  { to: '/admin/dashboard', label: '运营总览', icon: House, title: '运营总览' },
  { to: '/admin/documents', label: '文档接入', icon: Files, title: '文档接入' },
  { to: '/admin/knowledge-route', label: '知识路由', icon: ShareNetwork, title: '知识路由' },
  { to: '/admin/knowledge-route/traces', label: '路由追踪', icon: Eye, title: '路由追踪' },
  { to: '/admin/observability', label: '对话观测', icon: TerminalWindow, title: '对话观测' },
];

export function AdminLayout() {
  const { logout } = useAuth();
  const { toast } = useToast();
  const nav = useNavigate();
  const location = useLocation();
  const [sidebarOpen, setSidebarOpen] = useState(false);

  const user = getAdminUser() as { username?: string } | null;
  const username = user?.username || 'admin';
  const pageTitle =
    [...NAV_ITEMS]
      .sort((a, b) => b.to.length - a.to.length)
      .find((item) => location.pathname.startsWith(item.to))?.title ?? '管理后台';

  const handleLogout = async () => {
    try {
      await logout();
    } catch {
      // token 失效或网络异常时仍允许本地退出
    } finally {
      toast('已退出登录', 'info');
      nav('/admin/login', { replace: true });
    }
  };

  const closeSidebar = () => setSidebarOpen(false);

  const SidebarContent = () => (
    <div className="flex flex-col h-full">
      <div className="px-5 py-5 border-b border-neutral-800 flex items-center justify-between">
        <div className="flex items-center gap-2.5">
          <div className="w-8 h-8 rounded-md bg-amber-500 grid place-items-center">
            <span className="text-[11px] font-bold text-black tracking-wider">RA</span>
          </div>
          <div>
            <div className="text-sm font-semibold text-neutral-100 tracking-tight">reuben-agent</div>
            <div className="text-[10px] text-neutral-500 font-mono uppercase tracking-wider">Admin Console</div>
          </div>
        </div>
        <button
          onClick={closeSidebar}
          className="md:hidden w-7 h-7 grid place-items-center rounded-md text-neutral-500 hover:bg-neutral-800"
        >
          <X className="w-4 h-4" />
        </button>
      </div>

      <nav className="flex-1 px-3 py-4 space-y-0.5">
        <p className="px-3 py-2 text-[10px] font-mono uppercase tracking-wider text-neutral-600">主要功能</p>
        {NAV_ITEMS.map((item) => {
          const Icon = item.icon;
          const active = location.pathname === item.to;
          return (
            <NavLink
              key={item.to}
              to={item.to}
              onClick={closeSidebar}
              className={cn(
                'flex items-center gap-3 px-3 py-2 rounded-lg text-sm transition-colors duration-150',
                active
                  ? 'bg-amber-500/10 text-amber-400 font-medium'
                  : 'text-neutral-400 hover:text-neutral-200 hover:bg-neutral-800/50',
              )}
            >
              <Icon weight={active ? 'fill' : 'regular'} className="w-4.5 h-4.5 shrink-0" />
              <span>{item.label}</span>
            </NavLink>
          );
        })}
      </nav>

      <div className="px-3 py-3 border-t border-neutral-800">
        <div className="flex items-center justify-between gap-2 p-2 rounded-lg bg-neutral-900/60">
          <div className="flex items-center gap-2.5 min-w-0">
            <div className="w-8 h-8 rounded-full bg-amber-500/20 text-amber-400 grid place-items-center text-xs font-semibold shrink-0">
              {username.slice(0, 1).toUpperCase()}
            </div>
            <div className="min-w-0">
              <p className="text-xs font-medium text-neutral-200 truncate">{username}</p>
              <p className="text-[10px] text-neutral-500">管理员</p>
            </div>
          </div>
          <button
            onClick={handleLogout}
            title="退出登录"
            className="shrink-0 w-7 h-7 grid place-items-center rounded-md text-neutral-500 hover:text-red-400 hover:bg-red-500/10 transition-colors"
          >
            <SignOut className="w-4 h-4" />
          </button>
        </div>
      </div>
    </div>
  );

  return (
    <div className="min-h-screen md:grid md:grid-cols-[220px_1fr]">
      {/* 桌面侧边栏 */}
      <aside className="hidden md:flex sticky top-0 h-screen bg-neutral-950 border-r border-neutral-800">
        <SidebarContent />
      </aside>

      {/* 移动端侧边抽屉 */}
      {sidebarOpen && (
        <div className="md:hidden fixed inset-0 z-40 flex">
          <div className="w-[240px] h-full bg-neutral-950 border-r border-neutral-800" onClick={(e) => e.stopPropagation()}>
            <SidebarContent />
          </div>
          <div className="flex-1 bg-black/50" onClick={closeSidebar} />
        </div>
      )}

      {/* 主区域 */}
      <div className="flex flex-col min-w-0 min-h-screen bg-neutral-950">
        <header className="sticky top-0 z-30 flex items-center justify-between gap-4 px-5 h-[52px] border-b border-neutral-800 bg-neutral-950/95 backdrop-blur-sm">
          <div className="flex items-center gap-3">
            <button
              onClick={() => setSidebarOpen(true)}
              className="md:hidden w-9 h-9 grid place-items-center rounded-md border border-neutral-800 text-neutral-300"
            >
              <List className="w-4 h-4" />
            </button>
            <h2 className="text-base font-semibold text-neutral-100 hidden md:block">{pageTitle}</h2>
            <nav className="hidden lg:flex items-center gap-1.5 text-xs text-neutral-500">
              <NavLink to="/admin/dashboard" className="hover:text-amber-400">首页</NavLink>
              <span className="text-neutral-700">/</span>
              <span className="text-neutral-400">{pageTitle}</span>
            </nav>
          </div>

          <div className="flex items-center gap-4">
            <NavLink
              to="/chat"
              className="flex items-center gap-1.5 text-xs text-amber-400 font-medium hover:opacity-80"
            >
              <ArrowSquareLeft className="w-3.5 h-3.5" />
              返回聊天
            </NavLink>
            <span className="hidden sm:block w-px h-5 bg-neutral-800" />
            <div className="hidden sm:flex items-center gap-2 text-xs text-neutral-300">
              <span>{username}</span>
              <div className="w-7 h-7 rounded-full bg-neutral-800 border border-neutral-700 text-neutral-200 grid place-items-center text-[11px] font-semibold">
                {username.slice(0, 1).toUpperCase()}
              </div>
            </div>
          </div>
        </header>

        <main className="flex-1 px-5 md:px-6 py-5 overflow-y-auto">
          <Outlet />
        </main>
      </div>
    </div>
  );
}

export function AdminPage({ children }: { children: ReactNode }) {
  return <div className="max-w-[1200px] mx-auto">{children}</div>;
}
