import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { MemberRegistrationForm } from './MemberRegistrationForm';
import { ApiError, DuplicateEmailError, ValidationError } from '../api/errors';

/**
 * The registration form is a presentational component whose collaborator is the
 * `useRegisterMember` mutation hook. We mock that hook so we can drive the
 * per-call `onSuccess` / `onError` callbacks deterministically and toggle the
 * `isPending` flag — this isolates the component's own logic (the load-bearing
 * `instanceof` error-discrimination chain, the reset-on-success behaviour and
 * the client-side Zod validation gate) from the network / query stack.
 */
const { mutateMock, mutationState } = vi.hoisted(() => ({
  mutateMock: vi.fn(),
  mutationState: { isPending: false },
}));

vi.mock('../hooks/useRegisterMember', () => ({
  useRegisterMember: () => ({
    mutate: mutateMock,
    isPending: mutationState.isPending,
  }),
}));

/** Fills the three fields with values that satisfy the client Zod schema. */
async function fillValidForm(user: ReturnType<typeof userEvent.setup>) {
  await user.type(screen.getByLabelText('Name'), 'Jane Doe');
  await user.type(screen.getByLabelText('Email'), 'jane@example.com');
  await user.type(screen.getByLabelText('Phone'), '1234567890');
}

const input = (label: string) => screen.getByLabelText(label) as HTMLInputElement;

beforeEach(() => {
  mutateMock.mockReset();
  mutationState.isPending = false;
});

afterEach(() => {
  vi.clearAllMocks();
});

describe('MemberRegistrationForm', () => {
  it('renders the registration form with heading, three labelled fields and the Register button', () => {
    render(<MemberRegistrationForm />);

    expect(
      screen.getByRole('heading', { name: 'Member Registration' }),
    ).toBeInTheDocument();
    expect(screen.getByLabelText('Name')).toBeInTheDocument();
    expect(screen.getByLabelText('Email')).toBeInTheDocument();
    expect(screen.getByLabelText('Phone')).toBeInTheDocument();

    const button = screen.getByRole('button', { name: 'Register' });
    expect(button).toBeEnabled();
    // No error regions on first render.
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  // Acceptance criterion #1 — successful registration clears the form.
  it('submits valid input to the mutation and resets the form on success', async () => {
    const user = userEvent.setup();
    mutateMock.mockImplementation((_values, options) => {
      options?.onSuccess?.();
    });

    render(<MemberRegistrationForm />);
    await fillValidForm(user);
    await user.click(screen.getByRole('button', { name: 'Register' }));

    expect(mutateMock).toHaveBeenCalledTimes(1);
    expect(mutateMock).toHaveBeenCalledWith(
      { name: 'Jane Doe', email: 'jane@example.com', phoneNumber: '1234567890' },
      expect.objectContaining({
        onSuccess: expect.any(Function),
        onError: expect.any(Function),
      }),
    );

    // reset() ≙ initNewMember(): all three fields return to empty strings.
    await waitFor(() => {
      expect(input('Name').value).toBe('');
      expect(input('Email').value).toBe('');
      expect(input('Phone').value).toBe('');
    });
  });

  // Acceptance criterion #2 — a server 400 field-map populates per-field errors.
  it('maps a ValidationError field-map onto per-field error messages', async () => {
    const user = userEvent.setup();
    mutateMock.mockImplementation((_values, options) => {
      options?.onError?.(
        new ValidationError({
          name: 'size must be between 1 and 25',
          email: 'must be a well-formed email address',
          // Empty message must be skipped by the `if (message)` guard.
          phoneNumber: '',
        }),
      );
    });

    render(<MemberRegistrationForm />);
    await fillValidForm(user);
    await user.click(screen.getByRole('button', { name: 'Register' }));

    expect(
      await screen.findByText('size must be between 1 and 25'),
    ).toBeInTheDocument();
    expect(
      screen.getByText('must be a well-formed email address'),
    ).toBeInTheDocument();
    // The empty-string phone message was skipped, so no third field error.
    expect(screen.getAllByRole('alert')).toHaveLength(2);
  });

  // Acceptance criterion #3 — a 409 duplicate email surfaces on the email field.
  it('shows the duplicate-email message on the email field for a DuplicateEmailError', async () => {
    const user = userEvent.setup();
    mutateMock.mockImplementation((_values, options) => {
      options?.onError?.(new DuplicateEmailError('Email taken'));
    });

    render(<MemberRegistrationForm />);
    await fillValidForm(user);
    await user.click(screen.getByRole('button', { name: 'Register' }));

    expect(await screen.findByText('Email taken')).toBeInTheDocument();
    // Only the email field carries an error.
    expect(screen.getAllByRole('alert')).toHaveLength(1);
  });

  // Generic ApiError ({ error }) is shown in the form-level region.
  it('renders a generic ApiError message in the form-level error region', async () => {
    const user = userEvent.setup();
    mutateMock.mockImplementation((_values, options) => {
      options?.onError?.(
        new ApiError('Registration failed. See server log for more information', 400),
      );
    });

    render(<MemberRegistrationForm />);
    await fillValidForm(user);
    await user.click(screen.getByRole('button', { name: 'Register' }));

    expect(
      await screen.findByText(
        'Registration failed. See server log for more information',
      ),
    ).toBeInTheDocument();
  });

  // Unexpected (non-typed) errors fall back to the generic message.
  it('falls back to a generic message for an unexpected non-typed error', async () => {
    const user = userEvent.setup();
    mutateMock.mockImplementation((_values, options) => {
      options?.onError?.(new Error('network unreachable'));
    });

    render(<MemberRegistrationForm />);
    await fillValidForm(user);
    await user.click(screen.getByRole('button', { name: 'Register' }));

    expect(
      await screen.findByText('Registration failed. Please try again.'),
    ).toBeInTheDocument();
  });

  // Client-side Zod validation blocks submission (mutation never called).
  it('blocks submission and shows Zod validation messages for invalid input', async () => {
    const user = userEvent.setup();

    render(<MemberRegistrationForm />);
    await user.type(screen.getByLabelText('Name'), 'John123');
    await user.type(screen.getByLabelText('Email'), 'not-an-email');
    await user.type(screen.getByLabelText('Phone'), '12');
    await user.click(screen.getByRole('button', { name: 'Register' }));

    expect(await screen.findByText('Must not contain numbers')).toBeInTheDocument();
    expect(screen.getByText('Invalid email address')).toBeInTheDocument();
    expect(
      screen.getByText('Phone number must be between 10 and 12 digits'),
    ).toBeInTheDocument();
    expect(mutateMock).not.toHaveBeenCalled();
  });

  // The submit button is disabled while the mutation is pending.
  it('disables the Register button while the mutation is pending', () => {
    mutationState.isPending = true;

    render(<MemberRegistrationForm />);

    expect(screen.getByRole('button', { name: 'Register' })).toBeDisabled();
  });
});
