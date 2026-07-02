/**
 * Unit tests for the page container (`./MemberPage`).
 *
 * `MemberPage` is a thin composition root: it renders the registration form,
 * the "Members" heading, and the members list (reproducing the overall layout
 * of the legacy JSF index.xhtml). These tests render the REAL children inside a
 * `QueryClientProvider` and mock only the API module so the list resolves to a
 * deterministic empty state. We assert the composition — both headings and the
 * Register button are present, and the list renders its empty state.
 *
 * Vitest globals are enabled via vitest.config.ts (`globals: true`).
 */
import { render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

import { getMembers } from '../api/membersApi';
import { MemberPage } from './MemberPage';

vi.mock('../api/membersApi', () => ({
  getMembers: vi.fn(),
  createMember: vi.fn(),
}));

const mockedGetMembers = vi.mocked(getMembers);

function renderPage() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return render(
    <QueryClientProvider client={client}>
      <MemberPage />
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  mockedGetMembers.mockResolvedValue([]);
});

describe('MemberPage', () => {
  it('composes the registration form, the "Members" heading, and the list', async () => {
    renderPage();

    // Registration form (write side).
    expect(
      screen.getByRole('heading', { name: 'Member Registration' }),
    ).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Register' })).toBeInTheDocument();

    // The "Members" section heading precedes the list.
    expect(screen.getByRole('heading', { name: 'Members' })).toBeInTheDocument();

    // List (read side) resolves to the empty state with the mocked empty result.
    expect(await screen.findByText('No registered members.')).toBeInTheDocument();
  });
});
