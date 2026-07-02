/**
 * MemberRegistrationForm — the write/registration side of the Member screen
 * (Use Case 1).
 *
 * Reproduces the legacy JSF `<h:form id="reg">` from
 * kitchensink/src/main/webapp/index.xhtml (L31-60) and the controller action
 * `MemberController.register()` / `initNewMember()` (L52-63). Client validation
 * is provided by React Hook Form + `zodResolver(memberSchema)`, mirroring the
 * server's Bean Validation (Member.java L30-44). Submission goes through the
 * `useRegisterMember` mutation (`POST /rest/members`); on success the form is
 * cleared via `reset()` (parity with `initNewMember()`), and the members list
 * refetches because `useRegisterMember` invalidates the `['members']` query in
 * its own `onSuccess` (AAP §0.6.1) — no manual refetch here.
 *
 * The `onError` `instanceof` chain is the anti-corruption boundary that turns
 * the typed backend errors (from `../api/errors`, which normalize the JSF-era
 * wire shapes in MemberResourceRESTService.java L104-116) into user-visible
 * feedback matching the original screen:
 *   - ValidationError    (400 field map)   -> per-field errors      (criterion #2)
 *   - DuplicateEmailError (409)             -> email-field error     (criterion #3)
 *   - ApiError           (generic 400)      -> form-level error region
 *   - anything else                          -> generic fallback message
 * The order matters: the specific subclasses are checked before the base
 * `ApiError`.
 */
import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { memberSchema, type MemberFormValues } from '../validation/memberSchema';
import { useRegisterMember } from '../hooks/useRegisterMember';
import {
  ApiError,
  DuplicateEmailError,
  ValidationError,
  type FieldErrors,
} from '../api/errors';

export function MemberRegistrationForm() {
  const [formError, setFormError] = useState<string | null>(null);
  const {
    register,
    handleSubmit,
    reset,
    setError,
    formState: { errors },
  } = useForm<MemberFormValues>({
    resolver: zodResolver(memberSchema),
    defaultValues: { name: '', email: '', phoneNumber: '' },
  });
  const mutation = useRegisterMember();

  const onSubmit = (values: MemberFormValues) => {
    setFormError(null);
    mutation.mutate(values, {
      onSuccess: () => {
        reset();
      },
      onError: (error) => {
        if (error instanceof ValidationError) {
          (Object.keys(error.fieldErrors) as (keyof FieldErrors)[]).forEach((field) => {
            const message = error.fieldErrors[field];
            if (message) {
              setError(field, { type: 'server', message });
            }
          });
        } else if (error instanceof DuplicateEmailError) {
          setError('email', { type: 'server', message: error.message });
        } else if (error instanceof ApiError) {
          setFormError(error.message);
        } else {
          setFormError('Registration failed. Please try again.');
        }
      },
    });
  };

  return (
    <section>
      <h2>Member Registration</h2>
      <form onSubmit={handleSubmit(onSubmit)} noValidate>
        <div>
          <label htmlFor="name">Name</label>
          <input id="name" type="text" {...register('name')} />
          {errors.name && <span role="alert">{errors.name.message}</span>}
        </div>
        <div>
          <label htmlFor="email">Email</label>
          <input id="email" type="text" {...register('email')} />
          {errors.email && <span role="alert">{errors.email.message}</span>}
        </div>
        <div>
          <label htmlFor="phoneNumber">Phone</label>
          <input id="phoneNumber" type="text" {...register('phoneNumber')} />
          {errors.phoneNumber && <span role="alert">{errors.phoneNumber.message}</span>}
        </div>
        {formError && <div role="alert">{formError}</div>}
        <button type="submit" disabled={mutation.isPending}>
          Register
        </button>
      </form>
    </section>
  );
}
