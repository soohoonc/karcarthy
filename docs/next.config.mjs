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
        destination: '/docs/running-agents',
        permanent: true,
      },
      {
        source: '/docs/runtime',
        destination: '/docs/running-agents',
        permanent: true,
      },
      {
        source: '/docs/execution',
        destination: '/docs/running-agents',
        permanent: true,
      },
      {
        source: '/docs/reference/json',
        destination: '/docs/reference/generated-agents',
        permanent: true,
      },
      {
        source: '/docs/examples/python',
        destination: '/docs/integrations',
        permanent: true,
      },
      {
        source: '/docs/examples/typescript',
        destination: '/docs/integrations',
        permanent: true,
      },
    ];
  },
};

export default withMDX(config);
