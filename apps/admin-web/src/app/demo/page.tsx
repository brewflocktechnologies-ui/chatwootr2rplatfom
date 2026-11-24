import type { Metadata } from 'next';
import { ChatwootLoader } from './ChatwootLoader';

export const metadata: Metadata = {
  title: 'Acme Demo Web (sample page)',
  description: 'Sample customer-facing page with the Chatwoot website widget.',
};

// Customer-facing sample page. No admin navigation, no admin banner.
export default function DemoPage() {
  return (
    <main className="page demo-hero">
      <h1>Acme Demo Web</h1>
      <p>
        This is a fictional sample storefront used to test the support widget locally. Ask a question about
        returns, shipping or the warranty using the chat button.
      </p>
      <ChatwootLoader />
    </main>
  );
}
