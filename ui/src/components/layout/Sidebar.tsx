import { type ReactNode } from 'react';
import { NavLink, useLocation } from 'react-router-dom';
import {
  Files,
  MagnifyingGlass,
  ChatCircle,
  ChatCenteredDots,
  Flask,
} from '@phosphor-icons/react';
import type { Icon } from '@phosphor-icons/react';
import { cn } from '../../lib/cn';

interface NavItem {
  to: string;
  label: string;
  icon: Icon;
  soon?: boolean;
}

const items: NavItem[] = [
  { to: '/documents', label: '文档管理', icon: Files },
  { to: '/retrieval', label: '召回测试', icon: MagnifyingGlass },
  { to: '/chat', label: '对话', icon: ChatCircle },
  { to: '/conversations', label: '会话管理', icon: ChatCenteredDots },
];

export function Sidebar() {
  const location = useLocation();

  return (
    <aside className="flex flex-col h-screen sticky top-0 w-60 border-r border-neutral-800 bg-neutral-950 select-none">
      {/* Logo */}
      <div className="px-5 py-5 border-b border-neutral-800">
        <div className="flex items-center gap-2.5">
          <div className="w-7 h-7 rounded-md bg-amber-500 flex items-center justify-center">
            <Flask weight="bold" className="w-4 h-4 text-black" />
          </div>
          <div>
            <div className="text-sm font-semibold text-neutral-100 tracking-tight">
              reuben-agent
            </div>
            <div className="text-[10px] text-neutral-500 font-mono uppercase tracking-wider">
              RAG Testing
            </div>
          </div>
        </div>
      </div>

      {/* Nav */}
      <nav className="flex-1 px-3 py-4 space-y-0.5">
        {items.map((item) => {
          const Icon = item.icon;
          const active = location.pathname.startsWith(item.to);

          if (item.soon) {
            return (
              <div
                key={item.to}
                className="flex items-center gap-3 px-3 py-2 rounded-lg text-neutral-600 cursor-not-allowed"
              >
                <Icon weight="regular" className="w-4.5 h-4.5 shrink-0" />
                <span className="text-sm">{item.label}</span>
                <span className="ml-auto text-[10px] font-mono text-neutral-700">
                  SOON
                </span>
              </div>
            );
          }

          return (
            <NavLink
              key={item.to}
              to={item.to}
              className={cn(
                'flex items-center gap-3 px-3 py-2 rounded-lg text-sm transition-colors duration-150',
                active
                  ? 'bg-amber-500/10 text-amber-400 font-medium'
                  : 'text-neutral-400 hover:text-neutral-200 hover:bg-neutral-800/50',
              )}
            >
              <Icon
                weight={active ? 'fill' : 'regular'}
                className="w-4.5 h-4.5 shrink-0"
              />
              <span>{item.label}</span>
            </NavLink>
          );
        })}
      </nav>

      {/* Footer */}
      <div className="px-5 py-4 border-t border-neutral-800">
        <div className="text-[10px] text-neutral-600 font-mono">
          Spring Boot 3.5.6
        </div>
      </div>
    </aside>
  );
}
