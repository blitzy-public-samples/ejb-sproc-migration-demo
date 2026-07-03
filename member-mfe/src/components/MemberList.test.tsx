import { render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactElement } from 'react';
import { MemberList } from './MemberList';
import type { Member } from '../types/member';

function renderWithClient(ui: ReactElement) {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });
  return render(
    <QueryClientProvider client={queryClient}>{ui}</QueryClientProvider>,
  );
}

function mockFetchResolve(json: () => Promise<unknown>, ok = true, status = 200) {
  vi.stubGlobal(
    'fetch',
    vi.fn(async () => ({ ok, status, json }) as unknown as Response),
  );
}

describe('MemberList', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('shows a loading indicator while the members query is pending', () => {
    // A fetch that never resolves keeps the query in the pending state.
    vi.stubGlobal(
      'fetch',
      vi.fn(() => new Promise<Response>(() => {})),
    );

    renderWithClient(<MemberList />);

    expect(screen.getByText('Loading members…')).toBeInTheDocument();
  });

  it('shows the empty state when there are no members', async () => {
    mockFetchResolve(async () => []);

    renderWithClient(<MemberList />);

    expect(
      await screen.findByText('No registered members.'),
    ).toBeInTheDocument();
  });

  it('renders the members table in server order with columns Id, Name, Email, Phone', async () => {
    const members: Member[] = [
      { id: 1, name: 'Ann', email: 'ann@example.com', phoneNumber: '1112223333' },
      { id: 2, name: 'Bob', email: 'bob@example.com', phoneNumber: '4445556666' },
    ];
    mockFetchResolve(async () => members);

    renderWithClient(<MemberList />);

    // Wait for the table to render.
    expect(await screen.findByText('Ann')).toBeInTheDocument();

    // Column headers, in order.
    const headers = screen.getAllByRole('columnheader').map((h) => h.textContent);
    expect(headers).toEqual(['Id', 'Name', 'Email', 'Phone']);

    // Data rows appear in the server-returned order (Ann before Bob) — no client re-sort.
    const bodyRows = screen.getAllByRole('row').slice(1); // drop the header row
    expect(bodyRows).toHaveLength(2);
    expect(bodyRows[0]).toHaveTextContent('Ann');
    expect(bodyRows[1]).toHaveTextContent('Bob');

    // Cell values present.
    expect(screen.getByText('ann@example.com')).toBeInTheDocument();
    expect(screen.getByText('4445556666')).toBeInTheDocument();

    // Legacy JSF "REST URL" column + footer link are NOT reproduced.
    expect(screen.queryByText(/REST URL/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/\/rest\/members/i)).not.toBeInTheDocument();
  });

  it('shows the failure message when the members query fails', async () => {
    mockFetchResolve(async () => ({}), false, 500);

    renderWithClient(<MemberList />);

    expect(
      await screen.findByText('Unable to load members. Try again.'),
    ).toBeInTheDocument();
  });
});
