# karcarthy docs

This is the Fumadocs-powered documentation site for karcarthy. It is a Next.js
app intended to run as a Vercel project with `docs` as the project root.

Production URL: https://karcarthy.vercel.app/docs

## Develop

```bash
cd docs
npm ci
npm run dev
```

Open `http://localhost:3000/docs`.

## Verify

```bash
npm run lint
npm run types:check
npm run build
```

## Content

Docs are written in MDX under `content/docs/`.

Useful app files:

- `source.config.ts` configures Fumadocs MDX collections.
- `src/lib/source.ts` adapts the collection to Fumadocs' source API.
- `src/app/docs/[[...slug]]/page.tsx` renders documentation pages.
- `src/app/api/search/route.ts` serves the built-in search index.
- `src/app/llms.txt/route.ts` and `src/app/llms-full.txt/route.ts` expose LLM-readable docs.

## Deploy

Set the Vercel project root directory to `docs`.

Recommended Vercel settings:

| Setting | Value |
| --- | --- |
| Framework preset | Next.js |
| Install command | `npm ci` |
| Build command | `npm run build` |
| Output directory | `.next` |

CLI deployment also works:

```bash
vercel --cwd docs
vercel --cwd docs --prod
```

The current Vercel alias is `karcarthy.vercel.app`.
