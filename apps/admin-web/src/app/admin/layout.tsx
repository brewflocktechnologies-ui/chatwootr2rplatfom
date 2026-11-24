import Link from 'next/link';
import type { ReactNode } from 'react';

export default function AdminLayout({ children }: { children: ReactNode }) {
  return (
    <>
      <header className="site-header">
        <div className="site-header__inner">
          <strong>Acme Support admin (acme-demo)</strong>
          <nav className="nav" aria-label="Admin">
            <Link href="/admin">Overview</Link>
            <Link href="/admin/knowledge">Knowledge</Link>
            <Link href="/admin/agent">Agent</Link>
            <Link href="/demo">Customer demo</Link>
          </nav>
        </div>
      </header>
      <main className="page">
        <div className="banner" role="note" data-testid="local-only-banner">
          <strong>Local only.</strong> This console has no end-user sign-in. The admin key stays on the server.
          Keep this port bound to 127.0.0.1 and never expose it to a LAN or the internet.
        </div>
        {children}
      </main>
    </>
  );
}
