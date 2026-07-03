import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { useMembers } from './useMembers';
import { getMembers } from '../api/membersApi';
import type { Member } from '../types/member';

vi.mock('../api/membersApi', () => ({
  getMembers: vi.fn(),
  createMember: vi.fn(),
}));

function createWrapper() {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });
  return function Wrapper({ children }: { children: ReactNode }) {
    return (
      <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
    );
  };
}

describe('useMembers', () => {
  afterEach(() => {
    vi.clearAllMocks();
  });

  it('fetches and returns the members list on success (order preserved, no re-sort)', async () => {
    const members: Member[] = [
      { id: 1, name: 'Ann', email: 'ann@example.com', phoneNumber: '1112223333' },
      { id: 2, name: 'Bob', email: 'bob@example.com', phoneNumber: '4445556666' },
    ];
    vi.mocked(getMembers).mockResolvedValue(members);

    const { result } = renderHook(() => useMembers(), { wrapper: createWrapper() });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(result.current.data).toEqual(members);
    expect(getMembers).toHaveBeenCalledTimes(1);
  });

  it('exposes the error state when the query fails (no retry)', async () => {
    vi.mocked(getMembers).mockRejectedValue(new Error('network down'));

    const { result } = renderHook(() => useMembers(), { wrapper: createWrapper() });

    await waitFor(() => expect(result.current.isError).toBe(true));

    // retry:false in the QueryClient => the query function runs exactly once.
    expect(getMembers).toHaveBeenCalledTimes(1);
  });
});
