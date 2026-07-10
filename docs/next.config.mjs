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
        destination: '/docs/runtime',
        permanent: true,
      },
      {
        source: '/docs/reference/json',
        destination: '/docs/reference/generated-agents',
        permanent: true,
      },
      {
        source: '/docs/examples/python',
        destination: '/docs/runtime',
        permanent: true,
      },
      {
        source: '/docs/examples/typescript',
        destination: '/docs/runtime',
        permanent: true,
      },
    ];
  },
};

export default withMDX(config);
