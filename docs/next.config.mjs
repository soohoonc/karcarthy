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
        destination: '/docs/reference/eval',
        permanent: true,
      },
      {
        source: '/docs/reference/generated-agents',
        destination: '/docs/reference/eval',
        permanent: true,
      },
      {
        source: '/docs/reference/model-authored-agents',
        destination: '/docs/reference/eval',
        permanent: true,
      },
      {
        source: '/docs/reference/schema',
        destination: '/docs/reference/data',
        permanent: true,
      },
      {
        source: '/docs/repl',
        destination: '/docs/quickstart#develop-at-the-repl',
        permanent: true,
      },
      {
        source: '/docs/streaming',
        destination: '/docs/events',
        permanent: true,
      },
      {
        source: '/docs/harbor',
        destination: '/docs/guides/harbor',
        permanent: true,
      },
      {
        source: '/docs/guides/architect',
        destination: '/docs/guides/review',
        permanent: true,
      },
      {
        source: '/docs/mcp',
        destination: '/docs/protocols/mcp',
        permanent: true,
      },
      {
        source: '/docs/acp',
        destination: '/docs/protocols/acp',
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
      {
        source: '/docs/examples/coding-agent',
        destination: '/docs/guides/coding',
        permanent: true,
      },
      {
        source: '/docs/examples',
        destination: '/docs/guides',
        permanent: true,
      },
      {
        source: '/docs/examples/:path*',
        destination: '/docs/guides/:path*',
        permanent: true,
      },
    ];
  },
};

export default withMDX(config);
