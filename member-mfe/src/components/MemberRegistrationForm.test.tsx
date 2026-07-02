/**
 * Unit tests for the registration form (`./MemberRegistrationForm`).
 *
 * This is the write side of the Member screen (Use Case 1) and the most
 * acceptance-criteria-dense component. Strategy: mock ONLY the outermost seam —
 * `../api/membersApi.createMember` — and render the form inside a REAL
 * `QueryClientProvider` (retry:false, mirroring production `QueryProvider`).
 * That exercises the genuine `useRegisterMember` mutation, the real React Hook
 * Form + `zodResolver(memberSchema)` client validation, and the real
 * `../api/errors` typed-error mapping end-to-end — nothing about the component's
 * behavior is stubbed.
 *
 * Acceptance criteria covered:
 *   #1 success -> `createMember` called with the payload, form clears (reset).
 *   #2 400 ValidationError -> per-field errors from the server field map.
 *   #3 409 DuplicateEmailError -> duplicate-email message on the email field.
 *   plus: client validation blocks an invalid submit (no API call), a generic
 *   `ApiError` shows in the form-level region, and an unexpected error shows the
 *   generic fallback.
 *
 * `retry: false` is essential: TanStack Query v5 otherwise retries mutations
 * with exponential backoff, so error assertions would not resolve promptly.
 * Vitest globals are enabled via vitest.config.ts (`globals: true`).
 */
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

import { createMember } from '../api/membersApi';
import { ApiError, DuplicateEmailError, ValidationError } from '../api/errors';
import { MemberRegistrationForm } from './MemberRegistrationForm';

// Mock the API module: `createMember` is driven per-test; `getMembers` is stubbed
// only so the module mock is complete (this component never calls it).
vi.mock('../api/membersApi', () => ({
  createMember: vi.fn(),
  getMembers: vi.fn(),
}));

const mockedCreateMember = vi.mocked(createMember);

/** A fresh no-retry QueryClient per render, matching the production provider. */
function makeQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });
}

function renderForm() {
  return render(
    <QueryClientProvider client={makeQueryClient()}>
      <MemberRegistrationForm />
    </QueryClientProvider>,
  );
}

const validInput = {
  name: 'Jane Doe',
  email: 'jane@example.com',
  phoneNumber: '1234567890',
};

/** Types valid values into all three fields (passes client-side Zod validation). */
async function fillValid(user: ReturnType<typeof userEvent.setup>): Promise<void> {
  await user.type(screen.getByLabelText('Name'), validInput.name);
  await user.type(screen.getByLabelText('Email'), validInput.email);
  await user.type(screen.getByLabelText('Phone'), validInput.phoneNumber);
}

beforeEach(() => {
  vi.clearAllMocks();
});

describe('MemberRegistrationForm', () => {
  it('blocks an invalid submit with a client-side validation error and calls no API', async () => {
    const user = userEvent.setup();
    renderForm();

    // A name containing digits violates the Zod pattern (mirrors the server
    // @Pattern("[^0-9]*")). The other fields are valid so only `name` fails.
    await user.type(screen.getByLabelText('Name'), 'John123');
    await user.type(screen.getByLabelText('Email'), validInput.email);
    await user.type(screen.getByLabelText('Phone'), validInput.phoneNumber);
    await user.click(screen.getByRole('button', { name: 'Register' }));

    expect(await screen.findByText('Must not contain numbers')).toBeInTheDocument();
    // handleSubmit must NOT invoke the mutation when Zod validation fails.
    expect(mockedCreateMember).not.toHaveBeenCalled();
  });

  it('submits valid input and clears the form on success (acceptance #1)', async () => {
    mockedCreateMember.mockResolvedValue(undefined);
    const user = userEvent.setup();
    renderForm();

    await fillValid(user);
    await user.click(screen.getByRole('button', { name: 'Register' }));

    await waitFor(() => expect(mockedCreateMember).toHaveBeenCalledTimes(1));
    // TanStack Query v5 passes a second mutation-context argument to the
    // mutationFn; `createMember` ignores it, so assert only the payload (arg 0).
    expect(mockedCreateMember.mock.calls[0]?.[0]).toEqual(validInput);

    // On success the form resets to its empty defaultValues.
    await waitFor(() => expect(screen.getByLabelText('Name')).toHaveValue(''));
    expect(screen.getByLabelText('Email')).toHaveValue('');
    expect(screen.getByLabelText('Phone')).toHaveValue('');
  });

  it('maps a 400 ValidationError to per-field errors (acceptance #2)', async () => {
    mockedCreateMember.mockRejectedValue(
      new ValidationError({
        email: 'must be a well-formed email address',
        name: 'size must be between 1 and 25',
      }),
    );
    const user = userEvent.setup();
    renderForm();

    await fillValid(user);
    await user.click(screen.getByRole('button', { name: 'Register' }));

    expect(
      await screen.findByText('must be a well-formed email address'),
    ).toBeInTheDocument();
    expect(screen.getByText('size must be between 1 and 25')).toBeInTheDocument();
  });

  it('maps a 409 DuplicateEmailError to the email field (acceptance #3)', async () => {
    mockedCreateMember.mockRejectedValue(new DuplicateEmailError('Email taken'));
    const user = userEvent.setup();
    renderForm();

    await fillValid(user);
    await user.click(screen.getByRole('button', { name: 'Register' }));

    expect(await screen.findByText('Email taken')).toBeInTheDocument();
  });

  it('shows a form-level error for a generic ApiError', async () => {
    mockedCreateMember.mockRejectedValue(new ApiError('Service unavailable', 400));
    const user = userEvent.setup();
    renderForm();

    await fillValid(user);
    await user.click(screen.getByRole('button', { name: 'Register' }));

    expect(await screen.findByText('Service unavailable')).toBeInTheDocument();
  });

  it('shows the generic fallback message for an unexpected error', async () => {
    mockedCreateMember.mockRejectedValue(new Error('boom'));
    const user = userEvent.setup();
    renderForm();

    await fillValid(user);
    await user.click(screen.getByRole('button', { name: 'Register' }));

    expect(
      await screen.findByText('Registration failed. Please try again.'),
    ).toBeInTheDocument();
  });
});
