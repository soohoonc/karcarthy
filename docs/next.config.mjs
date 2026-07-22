import { createMDX } from 'fumadocs-mdx/next';

const withMDX = createMDX();

/** @type {import('next').NextConfig} */
const config = {
  reactStrictMode: true,
  async redirects() {
    return [
      {
        source: '/docs/workflows',
        destination: '/docs/agent-orchestration',
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
        source: '/docs/integrations',
        destination: '/docs/protocols/models',
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
        destination: '/docs/quickstart#run-it',
        permanent: true,
      },
      {
        source: '/docs/streaming',
        destination: '/docs/events',
        permanent: true,
      },
      {
        source: '/docs/harbor',
        destination: '/docs/protocols/harbor',
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
        destination: '/docs/protocols/models',
        permanent: true,
      },
      {
        source: '/docs/examples/typescript',
        destination: '/docs/protocols/models',
        permanent: true,
      },
      {
        source: '/docs/examples/coding-agent',
        destination: '/docs/guides/coding',
        permanent: true,
      },
      {
        source: '/docs/examples/composition',
        destination: '/docs/agent-orchestration#orchestrate-in-code',
        permanent: true,
      },
      {
        source: '/docs/examples/harbor',
        destination: '/docs/protocols/harbor',
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
      {
        source: '/docs/eval',
        destination: '/docs/agent-orchestration#dynamic-orchestration',
        permanent: true,
      },
      {
        source: '/docs/limits-safety',
        destination: '/docs/running-agents#usage-and-limits',
        permanent: true,
      },
      {
        source: '/docs/guides/composition',
        destination: '/docs/agent-orchestration#orchestrate-in-code',
        permanent: true,
      },
      {
        source: '/docs/guides/harbor',
        destination: '/docs/protocols/harbor',
        permanent: true,
      },
    ];
  },
};

export default withMDX(config);
