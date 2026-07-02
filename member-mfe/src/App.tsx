import { QueryProvider } from './providers/QueryProvider';
import { MemberPage } from './components/MemberPage';

/**
 * Exposed Module Federation module `./MemberApp`.
 *
 * Self-contained: it supplies its own TanStack Query `QueryProvider` so the
 * remote functions under any host without the host providing a `QueryClient`.
 *
 * MUST be a DEFAULT export — hosts consume it via
 * `React.lazy(() => import('memberMfe/MemberApp'))`, which requires a default export.
 */
export default function App() {
  return (
    <QueryProvider>
      <MemberPage />
    </QueryProvider>
  );
}
