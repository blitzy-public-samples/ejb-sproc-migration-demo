/**
 * Unit tests for the exposed Module Federation module (`./App`, exposed as
 * `./MemberApp`).
 *
 * `App` is the self-contained remote entry: it wraps `MemberPage` in its own
 * `QueryProvider` so any host can mount it without supplying a `QueryClient`.
 * This test renders the REAL tree end-to-end (App -> QueryProvider ->
 * MemberPage -> MemberRegistrationForm + MemberList -> useMembers -> membersApi
 * -> httpClient -> fetch), mocking only the global `fetch`. It therefore also
 * covers `providers/QueryProvider.tsx` transitively.
 *
 * It proves the remote's default export renders a working screen: the
 * registration form is present and the members list, fed by App's own query
 * provider, settles into the empty state after a stubbed `GET /rest/members`
 * returns `[]`.
 *
 * Vitest globals are enabled via vitest.config.ts (`globals: true`).
 */
import { render, screen, waitFor } from '@testing-library/react';

import App from './App';

// A `fetch`-shaped mock so `fetchMock.mock.calls` is strongly typed.
const fetchMock =
  vi.fn<(input: RequestInfo | URL, init?: RequestInit) => Promise<Response>>();

beforeEach(() => {
  fetchMock.mockReset();
  // Stub every request as a 200 with an empty JSON array (the members list).
  fetchMock.mockResolvedValue({
    ok: true,
    status: 200,
    json: async () => [],
  } as unknown as Response);
  vi.stubGlobal('fetch', fetchMock);
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('App (exposed ./MemberApp)', () => {
  it('renders the Member screen under its own QueryProvider and shows the empty state', async () => {
    render(<App />);

    // The registration form renders immediately (App -> QueryProvider -> page).
    expect(
      screen.getByRole('heading', { name: 'Member Registration' }),
    ).toBeInTheDocument();

    // The list, driven by App's own QueryProvider, resolves to the empty state.
    expect(await screen.findByText('No registered members.')).toBeInTheDocument();

    // The list fetched from the `/members` endpoint (single fetch — no StrictMode).
    await waitFor(() => expect(fetchMock).toHaveBeenCalled());
    const requestedUrl = String(fetchMock.mock.calls[0]?.[0]);
    expect(requestedUrl.endsWith('/members')).toBe(true);
  });
});
