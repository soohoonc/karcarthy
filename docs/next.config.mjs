import { createMDX } from 'fumadocs-mdx/next';

const withMDX = createMDX();

/** @type {import('next').NextConfig} */
const config = {
  reactStrictMode: true,
  async redirects() {
    return [
      {
        source: '/docs/workflows',
        destination: '/docs/agents',
        permanent: true,
      },
      {
        source: '/docs/runners',
        destination: '/docs/agents',
        permanent: true,
      },
      {
        source: '/docs/runtime',
        destination: '/docs/agents',
        permanent: true,
      },
      {
        source: '/docs/execution',
        destination: '/docs/agents',
        permanent: true,
      },
      {
        source: '/docs/running-agents',
        destination: '/docs/agents',
        permanent: true,
      },
      {
        source: '/docs/integrations',
        destination: '/docs/models',
        permanent: true,
      },
      {
        source: '/docs/reference/json',
        destination: '/docs/reference/generated-agents',
        permanent: true,
      },
      {
        source: '/docs/examples/python',
        destination: '/docs/models',
        permanent: true,
      },
      {
        source: '/docs/examples/typescript',
        destination: '/docs/models',
        permanent: true,
      },
    ];
  },
};

export default withMDX(config);
