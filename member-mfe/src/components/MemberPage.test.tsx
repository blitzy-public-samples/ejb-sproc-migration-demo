import { render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactElement } from 'react';
import { MemberPage } from './MemberPage';

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

describe('MemberPage', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('renders the registration form and the members section', async () => {
    // MemberList fetches on mount; return an empty list so it settles to the empty state.
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => ({ ok: true, status: 200, json: async () => [] }) as unknown as Response),
    );

    renderWithClient(<MemberPage />);

    // Registration form is present (Register button) and the Members section heading renders.
    expect(screen.getByRole('button', { name: /register/i })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Members' })).toBeInTheDocument();

    // Let the members query settle (avoids act() warnings from the pending fetch).
    expect(await screen.findByText('No registered members.')).toBeInTheDocument();
  });
});
