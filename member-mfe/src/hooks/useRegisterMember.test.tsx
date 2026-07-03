import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { useRegisterMember } from './useRegisterMember';
import { createMember } from '../api/membersApi';
import type { NewMemberInput } from '../types/member';

vi.mock('../api/membersApi', () => ({
  getMembers: vi.fn(),
  createMember: vi.fn(),
}));

const validInput: NewMemberInput = {
  name: 'Jane Doe',
  email: 'jane@example.com',
  phoneNumber: '1234567890',
};

function makeClient() {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });
}

function renderWithClient(queryClient: QueryClient) {
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  );
  return renderHook(() => useRegisterMember(), { wrapper });
}

describe('useRegisterMember', () => {
  afterEach(() => {
    vi.clearAllMocks();
  });

  it('invalidates the members query on successful registration', async () => {
    vi.mocked(createMember).mockResolvedValue(undefined);

    const queryClient = makeClient();
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries');

    const { result } = renderWithClient(queryClient);

    result.current.mutate(validInput);

    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    // TanStack Query v5 invokes mutationFn as (variables, context) — where
    // context is { client, meta, mutationKey }. Assert the FIRST argument is
    // the user's input so the check stays robust to the framework-injected
    // context object (createMember only consumes its first parameter).
    expect(createMember).toHaveBeenCalledTimes(1);
    expect(vi.mocked(createMember).mock.calls[0][0]).toEqual(validInput);
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['members'] });
  });

  it('surfaces the error and does not retry when registration fails', async () => {
    const error = new Error('Email taken');
    vi.mocked(createMember).mockRejectedValue(error);

    const queryClient = makeClient();
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries');

    const { result } = renderWithClient(queryClient);

    result.current.mutate(validInput);

    await waitFor(() => expect(result.current.isError).toBe(true));

    expect(result.current.error).toBe(error);
    // retry:false => mutationFn invoked exactly once.
    expect(createMember).toHaveBeenCalledTimes(1);
    // onSuccess must NOT run on failure => no invalidation.
    expect(invalidateSpy).not.toHaveBeenCalled();
  });
});
