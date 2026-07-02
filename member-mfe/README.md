# member-mfe — Member Management Micro-Frontend

`member-mfe` is a standalone **React 18 + TypeScript** application compiled by
**Webpack 5 Module Federation** into a remote named **`memberMfe`** that exposes a
single module, **`./MemberApp`**, through a generated **`remoteEntry.js`**. It is the
micro-frontend replacement for the legacy JavaServer Faces (JSF) *Member* screen —
the registration form, the member listing, and the empty-state message — that used
to be server-rendered inside the `kitchensink` WAR.

The project lives at the repository root as a **sibling to `kitchensink/`**, but it is
deliberately **OUTSIDE the Maven reactor**: it is neither declared as a Maven module in
the root `pom.xml` nor packaged into the WAR. Its build-and-deploy lifecycle is fully
decoupled from the Java EE application. The remote's **only** backend contact point is
the existing, **unmodified** JAX-RS API at **`/rest/members`**; it reads and writes
members through that contract exactly as the JSF screen did, changing nothing about the
endpoint paths, HTTP verbs, status codes, or JSON shapes.

Because the remote is served from a different origin than the WAR, the backend adds one
narrowly scoped JAX-RS CORS filter under `/rest/*` so the cross-origin remote can reach
the API. That filter is the single authorized backend change; everything else on the
server (the REST resource, the domain model, the persistence and service tiers) is left
untouched.

## Prerequisites

- **Node 20 LTS** and **npm**. (The project declares `"engines": { "node": ">=20" }`.)
- A running `kitchensink` backend reachable at the URL configured in `MFE_REST_BASE_URL`
  (default `http://localhost:8080/kitchensink/rest`), with the scoped `/rest/*` CORS
  filter deployed. Without that filter the browser blocks the cross-origin API calls the
  remote makes, and both the member list and registration will fail in the browser.

If you only want to build, type-check, or run the unit tests, the backend is not required
— it is needed only when exercising the running UI against live data.

## Install / Build / Run / Test

```bash
npm install            # install dependencies
npm run build          # production bundle + dist/remoteEntry.js  (webpack.prod.js)
npm run dev            # standalone dev server on MFE_PORT (default 5001), serves remoteEntry.js
npm test               # vitest run --coverage  (>= 80% statement coverage enforced)
```

- `npm run build` produces the production bundle (via `webpack.prod.js`), emitting
  `dist/remoteEntry.js` — the Module Federation manifest a host loads to consume the
  remote.
- `npm run dev` serves the remote standalone for verification. Once it is up, open
  **`http://localhost:5001`** in a browser to see the Member screen rendered on its own.
- `npm test` runs the Vitest + React Testing Library suite once (non-watch) with V8
  coverage; the run fails if statement coverage drops below the enforced **80%** threshold.
- Continuous integration is handled by the workflow
  **`.github/workflows/member-mfe-ci.yml`** (maintained separately). It is scoped to this
  directory and runs `npm ci && npm run build && npm test` on changes under `member-mfe/`.

## Environment Variables

| Variable            | Description                                                        | Default                                     |
| ------------------- | ------------------------------------------------------------------ | ------------------------------------------- |
| `MFE_REST_BASE_URL` | Base URL of the `kitchensink` JAX-RS API the remote calls.         | `http://localhost:8080/kitchensink/rest`    |
| `MFE_PORT`          | Dev-server port and the origin that serves `remoteEntry.js`.       | `5001`                                       |

These variables are injected **at build time** by Webpack's `DefinePlugin` — there is **no
runtime `.env` loader**, and no build/dev/test script sources a `.env` file. To override a
default, **export** the variable in your shell before running a script, or set it **inline** for
a single command:

```bash
# Export for the shell session, then run any script:
export MFE_PORT=5001
export MFE_REST_BASE_URL=http://localhost:8080/kitchensink/rest
npm run dev

# …or set the variables inline for a single command:
MFE_PORT=5001 MFE_REST_BASE_URL=http://localhost:8080/kitchensink/rest npm run dev
```

The committed **`.env.example`** lists the variable names and their defaults **for reference
only** — it is a template and is **not** read at build or run time (the project has no `dotenv`
dependency), so editing a local `.env` has no effect on `npm run dev` or `npm run build`.

If you do not set them, the defaults above are used, which target a locally running
`kitchensink` WAR on `http://localhost:8080`.

## Module Federation contract

The remote publishes a small, fixed contract that any Module Federation host consumes:

- **Remote name:** `memberMfe`
- **Exposed module:** `./MemberApp` (implemented by `src/App`)
- **Output manifest:** `remoteEntry.js`
- **Shared singletons:** `react` and `react-dom` (declared `singleton: true` so the host
  and the remote share a single React instance rather than loading duplicates)

A host loads the exposed module lazily and renders it inside a `<Suspense>` boundary:

```tsx
// In a Module Federation host:
const MemberApp = React.lazy(() => import('memberMfe/MemberApp'));
// render inside <Suspense fallback={...}><MemberApp /></Suspense>
```

The local **`host-harness/`** directory is a **dev-only** Webpack host that consumes this
remote's `remoteEntry.js` to verify that the remote loads and mounts (this satisfies
acceptance criterion #9 of the migration). It exists purely for verification and is
**not** a deployable artifact.

The remote uses the canonical Module Federation **async bootstrap boundary**: the entry
module `src/index.tsx` performs `import('./bootstrap')` so Webpack can negotiate the shared
singletons before any React code executes. This deferred boundary is what prevents the
runtime error *"Shared module is not available for eager consumption."*

## Behavioral parity with the legacy JSF screen

The remote reproduces the three use cases of the original Facelets page plus one required
robustness enhancement (the list-load-failure state). Behavior — not pixel-level Facelets
styling — is preserved.

- **Registration.** A form with **Name**, **Email**, and **Phone** fields, validated on the
  client with **React Hook Form + Zod** (the Zod schema mirrors the server's Bean Validation
  rules). On a successful submit (`POST /rest/members` → `200`) the form clears and the member
  list refetches. A `400` (constraint violation) populates **per-field** errors from the
  server's field map; a `409` (duplicate email) shows a duplicate-email message on the
  **email** field.
- **Listing.** A table with columns **Id**, **Name**, **Email**, and **Phone**, ordered by
  name as returned by `GET /rest/members`. The legacy JSF **"REST URL"** column and the
  footer "REST URL for all members" link are legacy chrome and are intentionally **omitted**.
- **Empty state.** When no members exist, the list area shows `No registered members.`
- **Loading state.** While the members query is pending, a lightweight loading indicator is
  shown.
- **List-load-failure state (new, required).** If the members query fails, the list area
  shows `Unable to load members. Try again.` — a state the original JSF page did not implement
  but that the acceptance criteria require.

The original server-side CDI event-driven refresh (a successful registration rebuilt the
`@Named` members list) is reproduced entirely on the client: the registration mutation calls
TanStack Query's `invalidateQueries(['members'])` on success, which refetches the list. To
preserve the original screen's immediate error behavior, the `QueryClient` is created with
`retry: false` for **both** queries and mutations — there is no retry, backoff, or
circuit-breaker behavior.

## Project structure

```text
member-mfe/
├── package.json               # npm manifest: deps + scripts (install/build/dev/test)
├── package-lock.json          # exact resolutions (generated at npm install)
├── tsconfig.json              # TypeScript 5.4, strict: true
├── webpack.common.js          # shared build: ts-loader, resolve, ModuleFederationPlugin
├── webpack.dev.js             # dev server on MFE_PORT, mode development
├── webpack.prod.js            # mode production; emits dist/remoteEntry.js
├── vitest.config.ts           # jsdom env, @vitejs/plugin-react, v8 coverage >= 80%
├── vitest.setup.ts            # imports @testing-library/jest-dom matchers
├── .env.example               # MFE_REST_BASE_URL, MFE_PORT template
├── .gitignore
├── README.md                  # this file
├── public/
│   └── index.html             # standalone dev shell with <div id="root">
├── src/
│   ├── index.tsx              # MF entry: dynamic import('./bootstrap') async boundary
│   ├── bootstrap.tsx          # createRoot + render <App/> for standalone dev
│   ├── App.tsx                # EXPOSED as ./MemberApp; wraps providers around the page
│   ├── config/
│   │   └── env.ts             # resolves MFE_REST_BASE_URL / MFE_PORT
│   ├── providers/
│   │   └── QueryProvider.tsx  # QueryClient with retry:false + QueryClientProvider
│   ├── types/
│   │   └── member.ts          # Member type + NewMemberInput
│   ├── validation/
│   │   └── memberSchema.ts    # Zod schema mirroring Member Bean Validation
│   ├── api/
│   │   ├── httpClient.ts      # fetch wrapper: base URL, JSON headers, status handling
│   │   ├── membersApi.ts      # getMembers() / createMember() typed contract calls
│   │   └── errors.ts          # normalize 400 field-map / 409 duplicate / generic errors
│   ├── hooks/
│   │   ├── useMembers.ts      # useQuery(['members']) -> membersApi.getMembers
│   │   └── useRegisterMember.ts  # useMutation -> createMember; invalidate ['members']
│   └── components/
│       ├── MemberPage.tsx     # container: composes form + list
│       ├── MemberRegistrationForm.tsx  # RHF + zodResolver; per-field + form-level errors
│       └── MemberList.tsx     # table Id/Name/Email/Phone; loading/empty/error states
├── src/**/*.test.tsx          # Vitest + React Testing Library tests (co-located)
└── host-harness/              # DEV-ONLY host; not a deployable artifact
    ├── package.json
    ├── webpack.config.js      # host MF: remotes memberMfe@http://localhost:MFE_PORT/remoteEntry.js
    ├── public/
    │   └── index.html
    └── src/
        ├── index.tsx          # dynamic import('./bootstrap')
        ├── bootstrap.tsx
        └── App.tsx            # React.lazy(() => import('memberMfe/MemberApp')) in <Suspense>
```

## Tech stack

- **React 18.3** — UI runtime (Module Federation shared singleton).
- **TypeScript 5.4** — strict-mode type checking (`strict: true`).
- **Webpack 5 + Module Federation** — bundling and the `ModuleFederationPlugin` remote.
- **TanStack Query v5** — server-state for the member query and registration mutation
  (with `retry: false` to preserve no-retry parity).
- **React Hook Form** + **`@hookform/resolvers`** + **Zod** — registration form state and
  client-side validation mirroring the server's Bean Validation rules.
- **Vitest** + **React Testing Library** — component/unit testing with V8 coverage enforced
  at ≥ 80% statement coverage.

## License

This project is part of the JBoss / Red Hat quickstarts and is distributed under the
**Apache License, Version 2.0**, consistent with the rest of the repository.
