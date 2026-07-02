import React, { Suspense } from 'react';

/**
 * Host consumption of the Module Federation remote.
 *
 * `memberMfe/MemberApp` = remote key `memberMfe` + exposed module `./MemberApp`
 * (declared in ../webpack.config.js `remotes` and in the remote's
 * member-mfe/webpack.common.js `exposes`). It is loaded lazily so Webpack's
 * Module Federation runtime can fetch remoteEntry.js and negotiate the shared
 * React singleton BEFORE the remote renders.
 *
 * The host does NOT supply a QueryClient/QueryProvider — the remote's exposed
 * App already wraps its own QueryProvider. This harness only lazy-loads and
 * mounts the remote to prove remote loadability (acceptance criterion #9).
 */
const MemberApp = React.lazy(() => import('memberMfe/MemberApp'));

export default function App() {
  return (
    <Suspense fallback={<div>Loading…</div>}>
      <MemberApp />
    </Suspense>
  );
}
