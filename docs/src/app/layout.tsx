import { RootProvider } from 'fumadocs-ui/provider/next';
import './global.css';
import { Inter } from 'next/font/google';
import type { Metadata } from 'next';

const inter = Inter({
  subsets: ['latin'],
});

function metadataBase() {
  const host =
    process.env.NEXT_PUBLIC_VERCEL_PROJECT_PRODUCTION_URL ?? process.env.VERCEL_URL;

  if (!host) return new URL('http://localhost:3000');
  if (host.startsWith('http://') || host.startsWith('https://')) return new URL(host);
  return new URL(`https://${host}`);
}

export const metadata: Metadata = {
  metadataBase: metadataBase(),
  title: {
    default: 'karcarthy',
    template: '%s | karcarthy',
  },
  description: 'A small Clojure agent harness.',
};

export default function Layout({ children }: LayoutProps<'/'>) {
  return (
    <html lang="en" className={inter.className} suppressHydrationWarning>
      <body className="flex flex-col min-h-screen">
        <RootProvider>{children}</RootProvider>
      </body>
    </html>
  );
}
