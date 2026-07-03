import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactElement } from 'react';
import { MemberRegistrationForm } from './MemberRegistrationForm';
import { createMember } from '../api/membersApi';
import { ApiError, DuplicateEmailError, ValidationError } from '../api/errors';

vi.mock('../api/membersApi', () => ({
  getMembers: vi.fn(),
  createMember: vi.fn(),
}));

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

async function fillValidForm(user: ReturnType<typeof userEvent.setup>) {
  await user.type(screen.getByLabelText('Name'), 'Jane Doe');
  await user.type(screen.getByLabelText('Email'), 'jane@example.com');
  await user.type(screen.getByLabelText('Phone'), '1234567890');
}

describe('MemberRegistrationForm', () => {
  afterEach(() => {
    vi.clearAllMocks();
  });

  it('submits valid input and clears the form on success (criterion #1)', async () => {
    const user = userEvent.setup();
    vi.mocked(createMember).mockResolvedValue(undefined);

    renderWithClient(<MemberRegistrationForm />);
    await fillValidForm(user);
    await user.click(screen.getByRole('button', { name: /register/i }));

    // TanStack Query v5 invokes the mutationFn as `createMember(variables, context)`,
    // where `context` is the `{ client, meta, mutationKey }` MutationFunctionContext.
    // Assert the exact registration payload as the first argument and accept the
    // framework-supplied context as the second.
    await waitFor(() =>
      expect(createMember).toHaveBeenCalledWith(
        {
          name: 'Jane Doe',
          email: 'jane@example.com',
          phoneNumber: '1234567890',
        },
        expect.anything(),
      ),
    );

    // On success the form resets to empty defaults (mirrors initNewMember()).
    await waitFor(() => expect(screen.getByLabelText('Name')).toHaveValue(''));
    expect(screen.getByLabelText('Email')).toHaveValue('');
    expect(screen.getByLabelText('Phone')).toHaveValue('');
  });

  it('blocks submit and shows a client validation error when the name contains numbers', async () => {
    const user = userEvent.setup();

    renderWithClient(<MemberRegistrationForm />);
    await user.type(screen.getByLabelText('Name'), 'John123');
    await user.type(screen.getByLabelText('Email'), 'jane@example.com');
    await user.type(screen.getByLabelText('Phone'), '1234567890');
    await user.click(screen.getByRole('button', { name: /register/i }));

    expect(
      await screen.findByText('Must not contain numbers'),
    ).toBeInTheDocument();
    expect(createMember).not.toHaveBeenCalled();
  });

  it('maps a server 400 field-map onto per-field errors (criterion #2)', async () => {
    const user = userEvent.setup();
    vi.mocked(createMember).mockRejectedValue(
      new ValidationError({ name: 'size must be between 1 and 25' }),
    );

    renderWithClient(<MemberRegistrationForm />);
    await fillValidForm(user);
    await user.click(screen.getByRole('button', { name: /register/i }));

    expect(
      await screen.findByText('size must be between 1 and 25'),
    ).toBeInTheDocument();
  });

  it('shows the duplicate-email message on a 409 (criterion #3)', async () => {
    const user = userEvent.setup();
    vi.mocked(createMember).mockRejectedValue(
      new DuplicateEmailError('Email taken'),
    );

    renderWithClient(<MemberRegistrationForm />);
    await fillValidForm(user);
    await user.click(screen.getByRole('button', { name: /register/i }));

    expect(await screen.findByText('Email taken')).toBeInTheDocument();
  });

  it('shows a generic ApiError message in the form-level error region', async () => {
    const user = userEvent.setup();
    vi.mocked(createMember).mockRejectedValue(
      new ApiError('Registration failed. See server log for more information', 400),
    );

    renderWithClient(<MemberRegistrationForm />);
    await fillValidForm(user);
    await user.click(screen.getByRole('button', { name: /register/i }));

    expect(
      await screen.findByText(
        'Registration failed. See server log for more information',
      ),
    ).toBeInTheDocument();
  });
});
