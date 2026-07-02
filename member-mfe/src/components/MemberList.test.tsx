/**
 * Unit tests for the members table component (`./MemberList`).
 *
 * `MemberList` is the read/display side of the Member screen. It renders one of
 * four states off the `useMembers` (TanStack Query v5) result — loading, error,
 * empty, or a populated table — reproducing the legacy JSF `<h:dataTable>` and
 * `<h:panelGroup rendered="#{empty members}">` (index.xhtml L62-93) and adding
 * the new list-load-failure state (acceptance #6).
 *
 * Strategy: the `../hooks/useMembers` hook is mocked so each test can drive the
 * component into an exact query state without a QueryClient or network. The
 * hook itself is verified separately in `hooks/useMembers.test.tsx`; here we
 * assert only the component's state-branch selection, the exact user-visible
 * strings, the Id/Name/Email/Phone columns, and that server order is preserved
 * (no client-side re-sort).
 *
 * Acceptance criteria touched: #4 (table, server order), #5 (empty state),
 * #6 (list-load failure). See AAP §0.7.3.
 *
 * Vitest globals (`describe`/`it`/`expect`/`vi`/`beforeEach`) are enabled via
 * vitest.config.ts (`globals: true`).
 */
import { render, screen } from '@testing-library/react';

import type { Member } from '../types/member';
import { useMembers } from '../hooks/useMembers';
import { MemberList } from './MemberList';

// Mock the hook the component consumes so we control the query state directly.
vi.mock('../hooks/useMembers', () => ({
  useMembers: vi.fn(),
}));

const mockedUseMembers = vi.mocked(useMembers);
type UseMembersResult = ReturnType<typeof useMembers>;

/**
 * Installs a partial `useMembers` result for a single test. The component only
 * reads `data`, `isPending`, and `isError`; the remaining fields of the v5
 * query result are irrelevant here, so we cast the partial through `unknown`
 * (the single controlled cast permitted by the strict-TS rules).
 */
function mockState(partial: Partial<UseMembersResult>): void {
  mockedUseMembers.mockReturnValue(partial as unknown as UseMembersResult);
}

beforeEach(() => {
  vi.clearAllMocks();
});

describe('MemberList', () => {
  it('renders the loading indicator while the query is pending', () => {
    mockState({ data: undefined, isPending: true, isError: false });

    render(<MemberList />);

    expect(screen.getByText('Loading members…')).toBeInTheDocument();
  });

  it('renders the failure message when the query errors (acceptance #6)', () => {
    mockState({ data: undefined, isPending: false, isError: true });

    render(<MemberList />);

    expect(
      screen.getByText('Unable to load members. Try again.'),
    ).toBeInTheDocument();
  });

  it('renders the empty-state message when there are no members (acceptance #5)', () => {
    mockState({ data: [], isPending: false, isError: false });

    render(<MemberList />);

    expect(screen.getByText('No registered members.')).toBeInTheDocument();
  });

  it('treats undefined success data as empty via the `?? []` guard', () => {
    // Success with no data yet: the nullish-coalescing guard yields an empty
    // array, so the empty-state renders rather than crashing on `.length`.
    mockState({ data: undefined, isPending: false, isError: false });

    render(<MemberList />);

    expect(screen.getByText('No registered members.')).toBeInTheDocument();
  });

  it('renders the members table with Id/Name/Email/Phone columns (acceptance #4)', () => {
    const members: Member[] = [
      { id: 1, name: 'Ada Lovelace', email: 'ada@example.com', phoneNumber: '2125551234' },
      { id: 2, name: 'Grace Hopper', email: 'grace@example.com', phoneNumber: '2125555678' },
    ];
    mockState({ data: members, isPending: false, isError: false });

    render(<MemberList />);

    expect(screen.getByRole('columnheader', { name: 'Id' })).toBeInTheDocument();
    expect(screen.getByRole('columnheader', { name: 'Name' })).toBeInTheDocument();
    expect(screen.getByRole('columnheader', { name: 'Email' })).toBeInTheDocument();
    expect(screen.getByRole('columnheader', { name: 'Phone' })).toBeInTheDocument();

    // Row cells for both members are present.
    expect(screen.getByText('Ada Lovelace')).toBeInTheDocument();
    expect(screen.getByText('ada@example.com')).toBeInTheDocument();
    expect(screen.getByText('2125551234')).toBeInTheDocument();
    expect(screen.getByText('Grace Hopper')).toBeInTheDocument();
    expect(screen.getByText('grace@example.com')).toBeInTheDocument();
  });

  it('preserves the server order (no client-side re-sort)', () => {
    // Feed a deliberately non-alphabetical order; the table must render rows in
    // exactly that order (the server already orders by name).
    const serverOrder: Member[] = [
      { id: 10, name: 'Zoe Zephyr', email: 'zoe@example.com', phoneNumber: '2125559999' },
      { id: 11, name: 'Ada Lovelace', email: 'ada@example.com', phoneNumber: '2125551234' },
    ];
    mockState({ data: serverOrder, isPending: false, isError: false });

    render(<MemberList />);

    const tableText = screen.getByRole('table').textContent ?? '';
    expect(tableText.indexOf('Zoe Zephyr')).toBeLessThan(
      tableText.indexOf('Ada Lovelace'),
    );
  });
});
