import type { ReactNode } from 'react';
import { Sidebar } from './Sidebar';

export function AppShell({ children }: { children: ReactNode }) {
  return (
    <div className="grid min-h-screen" style={{ gridTemplateColumns: '240px 1fr' }}>
      <Sidebar />
      <main className="min-h-screen overflow-y-auto">
        <div className="max-w-[1200px] mx-auto px-8 py-8">
          {children}
        </div>
      </main>
    </div>
  );
}
