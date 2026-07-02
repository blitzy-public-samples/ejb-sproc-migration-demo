/**
 * Unit tests for the members-list hook (`./useMembers`).
 *
 * `useMembers` is the client-side server-state replacement for the legacy CDI
 * list producer (kitchensink/.../data/MemberListProducer.java): its
 * `@Produces @Named getMembers()` becomes a TanStack Query
 * `useQuery(['members'])` that calls `GET /rest/members` via `membersApi`.
 *
 * Strategy: the API collaborator (`../api/membersApi`) is mocked so we can drive
 * `getMembers` to resolve/reject at will, isolating the hook's wiring from the
 * transport. Each test wraps the hook in a `QueryClientProvider` whose client is
 * configured with `retry: false` — mirroring the production
 * `providers/QueryProvider.tsx` config so the error path resolves immediately
 * (TanStack Query v5 otherwise retries 3x with exponential backoff).
 *
 * Coverage of the (tiny) hook is total: the single `useQuery(['members'])`
 * expression is exercised on success, empty, and error, and the exact query key
 * `['members']` is asserted against the QueryClient cache — this is the key that
 * `useRegisterMember` invalidates on success (AAP 0.6.1), so it must not drift.
 *
 * Acceptance criteria touched: #4 (list rendered in server order — no client
 * re-sort), #5 (empty list read side), #6 (list-load failure -> isError). See
 * AAP 0.7.3.
 */
import type { ReactNode } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

import type { Member } from '../types/member';
import { getMembers } from '../api/membersApi';
import { useMembers } from './useMembers';

// Mock the API module so the hook's `queryFn` is fully controllable. Both this
// test and the hook under test import the SAME '../api/membersApi' module, so
// the mock applies uniformly. Only runtime is affected — the real type
// declarations remain, keeping strict-mode type-checking honest.
vi.mock('../api/membersApi', () => ({
  getMembers: vi.fn(),
}));

const mockedGetMembers = vi.mocked(getMembers);

/**
 * Builds a fresh `QueryClient` with retries disabled, matching the production
 * `QueryProvider` defaults. A new client per test guarantees an isolated cache.
 */
function makeQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false },
    },
  });
}

/**
 * Wraps the hook in a `QueryClientProvider` bound to the supplied client so the
 * test can inspect the same cache the hook writes to.
 */
function createWrapper(client: QueryClient) {
  return function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  };
}

beforeEach(() => {
  // Reset call history and any per-test resolved/rejected values between cases.
  vi.clearAllMocks();
});

describe('useMembers', () => {
  it('fetches via getMembers and exposes the parsed Member[] on success', async () => {
    const members: Member[] = [
      { id: 1, name: 'Ada Lovelace', email: 'ada@example.com', phoneNumber: '2125551234' },
      { id: 2, name: 'Grace Hopper', email: 'grace@example.com', phoneNumber: '2125555678' },
    ];
    mockedGetMembers.mockResolvedValue(members);

    const client = makeQueryClient();
    const { result } = renderHook(() => useMembers(), { wrapper: createWrapper(client) });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(mockedGetMembers).toHaveBeenCalledTimes(1);
    expect(result.current.data).toEqual(members);
    expect(result.current.isError).toBe(false);
  });

  it("uses the exact query key ['members'] that useRegisterMember invalidates", async () => {
    const members: Member[] = [
      { id: 7, name: 'Alan Turing', email: 'alan@example.com', phoneNumber: '2125550000' },
    ];
    mockedGetMembers.mockResolvedValue(members);

    const client = makeQueryClient();
    const { result } = renderHook(() => useMembers(), { wrapper: createWrapper(client) });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    // The data must be cached under EXACTLY ['members'] (byte-for-byte the key
    // useRegisterMember passes to invalidateQueries on success). Reading it back
    // by that key proves the hook registered under the shared cache key.
    expect(client.getQueryData(['members'])).toEqual(members);
  });

  it('preserves the server order (no client-side re-sort or transform)', async () => {
    // The server returns members ALREADY ordered by name (findAllOrderedByName);
    // the hook must pass the array through UNCHANGED. Feed a deliberately
    // non-alphabetical order and assert the identical order comes back.
    const serverOrder: Member[] = [
      { id: 10, name: 'Zoe Zephyr', email: 'zoe@example.com', phoneNumber: '2125559999' },
      { id: 11, name: 'Ada Lovelace', email: 'ada@example.com', phoneNumber: '2125551234' },
    ];
    mockedGetMembers.mockResolvedValue(serverOrder);

    const client = makeQueryClient();
    const { result } = renderHook(() => useMembers(), { wrapper: createWrapper(client) });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(result.current.data).toEqual(serverOrder);
    expect((result.current.data ?? []).map((m) => m.name)).toEqual([
      'Zoe Zephyr',
      'Ada Lovelace',
    ]);
  });

  it('exposes an empty array when no members exist (empty-state read side, acceptance #5)', async () => {
    mockedGetMembers.mockResolvedValue([]);

    const client = makeQueryClient();
    const { result } = renderHook(() => useMembers(), { wrapper: createWrapper(client) });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(result.current.data).toEqual([]);
  });

  it('surfaces the error (isError) when getMembers rejects — list-load failure, acceptance #6', async () => {
    const failure = new Error('Request failed with status 500');
    mockedGetMembers.mockRejectedValue(failure);

    const client = makeQueryClient();
    const { result } = renderHook(() => useMembers(), { wrapper: createWrapper(client) });

    await waitFor(() => expect(result.current.isError).toBe(true));

    // With retry disabled the failure surfaces immediately; the component layer
    // renders "Unable to load members. Try again." off this state.
    expect(result.current.error).toBe(failure);
    expect(result.current.data).toBeUndefined();
    expect(mockedGetMembers).toHaveBeenCalledTimes(1);
  });
});
