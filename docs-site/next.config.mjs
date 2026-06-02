import { createMDX } from 'fumadocs-mdx/next';

const withMDX = createMDX();

/** @type {import('next').NextConfig} */
const config = {
  reactStrictMode: true,
  async redirects() {
    return [
      {
        source: '/docs/tutorials',
        destination: '/docs/guides',
        permanent: true,
      },
      {
        source: '/docs/tutorials/:path*',
        destination: '/docs/guides/:path*',
        permanent: true,
      },
      {
        source: '/docs/runtime',
        destination: '/docs/state',
        permanent: true,
      },
      {
        source: '/docs/orchestrator-patterns',
        destination: '/docs/framework-mappings',
        permanent: true,
      },
    ];
  },
};

export default withMDX(config);
