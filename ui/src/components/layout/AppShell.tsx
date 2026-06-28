import { type ReactNode } from 'react';
import { Sidebar } from './Sidebar';

interface Props {
  children: ReactNode;
  /** 全屏模式：内容直接占满，不加内边距容器（Chat 双栏用）。 */
  bare?: boolean;
}

export function AppShell({ children, bare }: Props) {
  return (
    <div className="grid min-h-screen" style={{ gridTemplateColumns: '240px 1fr' }}>
      <Sidebar />
      {bare ? (
        <main className="min-h-screen overflow-hidden">{children}</main>
      ) : (
        <main className="min-h-screen overflow-y-auto">
          <div className="max-w-[1200px] mx-auto px-8 py-8">
            {children}
          </div>
        </main>
      )}
    </div>
  );
}
