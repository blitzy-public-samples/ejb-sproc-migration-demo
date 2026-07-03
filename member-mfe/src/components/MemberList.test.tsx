/**
 * Unit tests for `MemberList` — the read/display side of the Member screen.
 *
 * `MemberList` reproduces the legacy JSF `<h:dataTable value="#{members}">` and
 * the empty-state `<h:panelGroup rendered="#{empty members}">`
 * (kitchensink/src/main/webapp/index.xhtml L62-93) and adds the NEW
 * list-load-failure state required by acceptance criterion #6. It is a pure
 * presentational function of the `useMembers` (TanStack Query v5) result — it
 * never fetches directly.
 *
 * Strategy: the component's sole collaborator, the `../hooks/useMembers` hook,
 * is mocked so each test can pin the query result to exactly one of the four
 * states the component branches on — `{ data, isPending, isError }` — fully
 * deterministically and without any async timing. This isolates the rendering
 * logic from the query transport (the hook has its own suite in
 * `hooks/useMembers.test.tsx`).
 *
 * Branch order asserted (must never be reordered): (1) `isPending` -> loading,
 * (2) `isError` -> failure, (3) empty -> empty-state, (4) otherwise -> table.
 *
 * Acceptance criteria exercised: #4 (table Id/Name/Email/Phone in server
 * order, no client re-sort), #5 (empty state), #6 (list-load failure). Also
 * asserts the deliberate parity exclusion of the legacy JSF "REST URL" column
 * and footer link (AAP 0.4.2). Contributes to the >=80% coverage gate
 * (criterion #10). See AAP 0.4.2 and 0.7.3.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';

import type { Member } from '../types/member';
import { useMembers } from '../hooks/useMembers';
import { MemberList } from './MemberList';

// Replace the hook module so its return value is fully controllable per test.
// `MemberList` imports the SAME '../hooks/useMembers' module, so the mock
// applies to the component under test. Only runtime is replaced; the real type
// signature is preserved, keeping strict-mode type-checking honest.
vi.mock('../hooks/useMembers', () => ({
  useMembers: vi.fn(),
}));

const mockedUseMembers = vi.mocked(useMembers);

// The exact result type MemberList consumes, derived from the real hook
// signature so the mock never drifts from `useQuery<Member[]>`'s contract (and
// without importing `UseQueryResult` directly).
type MembersQueryResult = ReturnType<typeof useMembers>;

/**
 * Pins `useMembers` to a specific state. Only the three fields the component
 * reads (`data`, `isPending`, `isError`) are meaningful; the value is widened
 * to the full query-result type via `unknown` because the component reads
 * nothing else from the result.
 */
function mockQueryState(state: {
  data: Member[] | undefined;
  isPending: boolean;
  isError: boolean;
}): void {
  mockedUseMembers.mockReturnValue(state as unknown as MembersQueryResult);
}

beforeEach(() => {
  vi.clearAllMocks();
});

describe('MemberList', () => {
  it('renders the loading indicator while the query is pending (branch 1)', () => {
    mockQueryState({ data: undefined, isPending: true, isError: false });

    render(<MemberList />);

    // Exact string incl. the single-character ellipsis U+2026 (never "...").
    expect(screen.getByText('Loading members\u2026')).toBeInTheDocument();
    // Loading wins over every other branch: no table, no other messages.
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
    expect(screen.queryByText('No registered members.')).not.toBeInTheDocument();
    expect(
      screen.queryByText('Unable to load members. Try again.'),
    ).not.toBeInTheDocument();
  });

  it('renders the list-load-failure message when the query errored (branch 2, acceptance #6)', () => {
    mockQueryState({ data: undefined, isPending: false, isError: true });

    render(<MemberList />);

    expect(
      screen.getByText('Unable to load members. Try again.'),
    ).toBeInTheDocument();
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
    expect(screen.queryByText('Loading members\u2026')).not.toBeInTheDocument();
  });

  it('renders the empty-state message when the list is empty (branch 3, acceptance #5)', () => {
    mockQueryState({ data: [], isPending: false, isError: false });

    render(<MemberList />);

    expect(screen.getByText('No registered members.')).toBeInTheDocument();
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
  });

  it('treats undefined data as empty via the `data ?? []` guard (nullish fallback)', () => {
    // A settled, non-error query whose data is undefined must not throw on
    // `.length`/`.map`; the nullish-coalescing guard yields [] -> empty state.
    mockQueryState({ data: undefined, isPending: false, isError: false });

    render(<MemberList />);

    expect(screen.getByText('No registered members.')).toBeInTheDocument();
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
  });

  it('renders the members table with columns Id/Name/Email/Phone in server order (branch 4, acceptance #4)', () => {
    // Deliberately NON-alphabetical to prove the component does not re-sort:
    // the server already orders by name (findAllOrderedByName); the component
    // must render rows in the exact order it received them.
    const members: Member[] = [
      { id: 10, name: 'Zoe Zephyr', email: 'zoe@example.com', phoneNumber: '2125559999' },
      { id: 11, name: 'Ada Lovelace', email: 'ada@example.com', phoneNumber: '2125551234' },
    ];
    mockQueryState({ data: members, isPending: false, isError: false });

    render(<MemberList />);

    const table = screen.getByRole('table');
    expect(table).toBeInTheDocument();

    // Column headers, exactly Id / Name / Email / Phone (never "REST URL").
    const headers = within(table)
      .getAllByRole('columnheader')
      .map((cell) => cell.textContent);
    expect(headers).toEqual(['Id', 'Name', 'Email', 'Phone']);

    // Body rows preserve the server order (no client-side re-sort/transform).
    const bodyRows = within(table).getAllByRole('row').slice(1); // drop header row
    expect(bodyRows).toHaveLength(2);

    const firstRowCells = within(bodyRows[0])
      .getAllByRole('cell')
      .map((cell) => cell.textContent);
    expect(firstRowCells).toEqual(['10', 'Zoe Zephyr', 'zoe@example.com', '2125559999']);

    const secondRowCells = within(bodyRows[1])
      .getAllByRole('cell')
      .map((cell) => cell.textContent);
    expect(secondRowCells).toEqual([
      '11',
      'Ada Lovelace',
      'ada@example.com',
      '2125551234',
    ]);
  });

  it('omits the legacy JSF "REST URL" column and footer link (parity exclusion)', () => {
    const members: Member[] = [
      { id: 1, name: 'Grace Hopper', email: 'grace@example.com', phoneNumber: '2125555678' },
    ];
    mockQueryState({ data: members, isPending: false, isError: false });

    render(<MemberList />);

    // No "REST URL" header text and no /rest/members anchors anywhere.
    expect(screen.queryByText('REST URL')).not.toBeInTheDocument();
    expect(screen.queryByRole('link')).not.toBeInTheDocument();
    expect(document.querySelector('a[href*="/rest/members"]')).toBeNull();

    // Exactly the four parity columns remain.
    const headers = screen
      .getAllByRole('columnheader')
      .map((cell) => cell.textContent);
    expect(headers).toEqual(['Id', 'Name', 'Email', 'Phone']);
  });
});
