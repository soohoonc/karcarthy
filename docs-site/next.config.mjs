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
        destination: '/docs/mappings',
        permanent: true,
      },
      {
        source: '/docs/framework-mappings',
        destination: '/docs/mappings',
        permanent: true,
      },
      {
        source: '/docs/getting-started',
        destination: '/docs/start',
        permanent: true,
      },
      {
        source: '/docs/agent-sdks',
        destination: '/docs/sdks',
        permanent: true,
      },
      {
        source: '/docs/reference/workflow-schema',
        destination: '/docs/reference/schema',
        permanent: true,
      },
      {
        source: '/docs/reference/json-bridge',
        destination: '/docs/reference/json',
        permanent: true,
      },
    ];
  },
};

export default withMDX(config);
