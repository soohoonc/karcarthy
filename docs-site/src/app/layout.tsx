import { RootProvider } from 'fumadocs-ui/provider/next';
import './global.css';
import { Geist, Geist_Mono } from 'next/font/google';
import type { Metadata } from 'next';

const geistSans = Geist({
  subsets: ['latin'],
  variable: '--font-geist-sans',
});

const geistMono = Geist_Mono({
  subsets: ['latin'],
  variable: '--font-geist-mono',
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
    default: 'karcarthy docs',
    template: '%s | karcarthy docs',
  },
  description: 'Homoiconic agent orchestration for Clojure.',
};

export default function Layout({ children }: LayoutProps<'/'>) {
  return (
    <html
      lang="en"
      className={`${geistSans.variable} ${geistMono.variable}`}
      suppressHydrationWarning
    >
      <body className="flex flex-col min-h-screen">
        <RootProvider>{children}</RootProvider>
      </body>
    </html>
  );
}
