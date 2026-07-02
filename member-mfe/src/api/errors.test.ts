/**
 * Unit tests for the anti-corruption error adapter (`./errors`).
 *
 * `normalizeErrorResponse` translates the JSF-era backend error shapes returned
 * by the unchanged JAX-RS `MemberResourceRESTService` (see
 * kitchensink/src/main/java/org/jboss/as/quickstarts/kitchensink/rest/
 * MemberResourceRESTService.java, L104-116) into typed client errors:
 *
 *   - HTTP 409  -> DuplicateEmailError   (duplicate email conflict: `{ "email": "Email taken" }`)
 *   - HTTP 400  -> ApiError              (generic failure: `{ "error": "<message>" }`)
 *   - HTTP 400  -> ValidationError       (Bean Validation field map: `{ name?, email?, phoneNumber? }`)
 *   - any other -> ApiError              (fallback that drives the member-list error state)
 *
 * The suite exercises EVERY branch of the adapter (and of its `isRecord`
 * guard) so the project-wide >=80% v8 coverage threshold (AAP 0.7.3 #10) is
 * comfortably satisfied — this module is the highest-branching one in `api/`.
 * It validates acceptance criteria #2 (400 field-map), #3 (409 duplicate) and
 * #6 (generic/other -> fallback error state).
 *
 * Test environment: Vitest with `globals: true` (see vitest.config.ts), so
 * `describe` / `it` / `expect` are available without importing them.
 */

import {
  normalizeErrorResponse,
  ValidationError,
  DuplicateEmailError,
  ApiError,
} from './errors';

/**
 * Builds a minimal `Response`-like stub. A real DOM `Response` enforces
 * one-shot body consumption which is awkward for table-driven tests, so we
 * construct only the surface (`ok`, `status`, `json`) that
 * `normalizeErrorResponse` actually touches and cast it through `unknown`.
 * This is the single controlled cast permitted by the strict-TS rules.
 */
function fakeResponse(
  status: number,
  jsonImpl: () => Promise<unknown>,
  ok = false,
): Response {
  return { ok, status, json: jsonImpl } as unknown as Response;
}

/**
 * Awaits a promise expected to reject and returns the rejection value as
 * `unknown` for type-safe `instanceof` narrowing. Fails loudly (throws) if the
 * promise unexpectedly resolves — `normalizeErrorResponse` must ALWAYS throw.
 */
async function captureRejection(promise: Promise<unknown>): Promise<unknown> {
  try {
    await promise;
  } catch (error) {
    return error;
  }
  throw new Error('Expected normalizeErrorResponse to reject, but it resolved.');
}

describe('normalizeErrorResponse', () => {
  describe('409 Conflict -> DuplicateEmailError', () => {
    it('uses the server email message when the body is a record with a string email', async () => {
      const res = fakeResponse(409, async () => ({ email: 'Email taken' }));

      await expect(normalizeErrorResponse(res)).rejects.toBeInstanceOf(DuplicateEmailError);

      const error = await captureRejection(normalizeErrorResponse(res));
      expect(error).toBeInstanceOf(DuplicateEmailError);
      if (error instanceof DuplicateEmailError) {
        expect(error.message).toBe('Email taken');
      }
    });

    it('falls back to the default message when the body is undefined (non-record)', async () => {
      const error = await captureRejection(
        normalizeErrorResponse(fakeResponse(409, async () => undefined)),
      );
      expect(error).toBeInstanceOf(DuplicateEmailError);
      if (error instanceof DuplicateEmailError) {
        expect(error.message).toBe('Email taken');
      }
    });

    it('falls back to the default message when the body is a non-record string', async () => {
      const error = await captureRejection(
        normalizeErrorResponse(fakeResponse(409, async () => 'x')),
      );
      expect(error).toBeInstanceOf(DuplicateEmailError);
      if (error instanceof DuplicateEmailError) {
        expect(error.message).toBe('Email taken');
      }
    });

    it('falls back to the default message when the body is null (isRecord null guard)', async () => {
      const error = await captureRejection(
        normalizeErrorResponse(fakeResponse(409, async () => null)),
      );
      expect(error).toBeInstanceOf(DuplicateEmailError);
      if (error instanceof DuplicateEmailError) {
        expect(error.message).toBe('Email taken');
      }
    });

    it('falls back to the default message when the body is a record without a string email', async () => {
      const error = await captureRejection(
        normalizeErrorResponse(fakeResponse(409, async () => ({ email: 123, foo: 'bar' }))),
      );
      expect(error).toBeInstanceOf(DuplicateEmailError);
      if (error instanceof DuplicateEmailError) {
        expect(error.message).toBe('Email taken');
      }
    });
  });

  describe('400 Bad Request -> ApiError (generic { error })', () => {
    it('maps a generic { error } body to ApiError(400) before attempting the field-map', async () => {
      const res = fakeResponse(400, async () => ({ error: 'Unique Email Violation' }));

      await expect(normalizeErrorResponse(res)).rejects.toBeInstanceOf(ApiError);

      const error = await captureRejection(normalizeErrorResponse(res));
      expect(error).toBeInstanceOf(ApiError);
      if (error instanceof ApiError) {
        expect(error.status).toBe(400);
        expect(error.message).toBe('Unique Email Violation');
      }
    });
  });

  describe('400 Bad Request -> ValidationError (Bean Validation field map)', () => {
    it('maps multiple fields (name + email) and leaves phoneNumber undefined', async () => {
      const res = fakeResponse(400, async () => ({
        name: 'size must be between 1 and 25',
        email: 'must be a well-formed email address',
      }));

      await expect(normalizeErrorResponse(res)).rejects.toBeInstanceOf(ValidationError);

      const error = await captureRejection(normalizeErrorResponse(res));
      expect(error).toBeInstanceOf(ValidationError);
      if (error instanceof ValidationError) {
        expect(error.fieldErrors.name).toBe('size must be between 1 and 25');
        expect(error.fieldErrors.email).toBe('must be a well-formed email address');
        expect(error.fieldErrors.phoneNumber).toBeUndefined();
      }
    });

    it('maps a phoneNumber-only body (camelCase key, partial map)', async () => {
      const error = await captureRejection(
        normalizeErrorResponse(
          fakeResponse(400, async () => ({ phoneNumber: 'size must be between 10 and 12' })),
        ),
      );
      expect(error).toBeInstanceOf(ValidationError);
      if (error instanceof ValidationError) {
        expect(error.fieldErrors.phoneNumber).toBe('size must be between 10 and 12');
        expect(error.fieldErrors.name).toBeUndefined();
        expect(error.fieldErrors.email).toBeUndefined();
      }
    });
  });

  describe('fallback -> ApiError(`Request failed with status <n>`)', () => {
    it('400 with an empty record (no error key, no field keys) falls through to the fallback', async () => {
      const error = await captureRejection(
        normalizeErrorResponse(fakeResponse(400, async () => ({}))),
      );
      expect(error).toBeInstanceOf(ApiError);
      if (error instanceof ApiError) {
        expect(error.status).toBe(400);
        expect(error.message).toBe('Request failed with status 400');
      }
    });

    it('400 with a non-record (string) body falls through to the fallback', async () => {
      const res = fakeResponse(400, async () => 'oops');

      await expect(normalizeErrorResponse(res)).rejects.toBeInstanceOf(ApiError);

      const error = await captureRejection(normalizeErrorResponse(res));
      expect(error).toBeInstanceOf(ApiError);
      if (error instanceof ApiError) {
        expect(error.status).toBe(400);
        expect(error.message).toBe('Request failed with status 400');
      }
    });

    it('500 (member-list GET failure) maps to the fallback ApiError', async () => {
      const res = fakeResponse(500, async () => ({}));

      await expect(normalizeErrorResponse(res)).rejects.toBeInstanceOf(ApiError);

      const error = await captureRejection(normalizeErrorResponse(res));
      expect(error).toBeInstanceOf(ApiError);
      if (error instanceof ApiError) {
        expect(error.status).toBe(500);
        expect(error.message).toBe('Request failed with status 500');
      }
    });

    it('a non-JSON body (json() rejects) is swallowed and falls through to the fallback', async () => {
      const error = await captureRejection(
        normalizeErrorResponse(
          fakeResponse(400, async () => {
            throw new Error('bad json');
          }),
        ),
      );
      expect(error).toBeInstanceOf(ApiError);
      if (error instanceof ApiError) {
        expect(error.status).toBe(400);
        expect(error.message).toBe('Request failed with status 400');
      }
    });
  });
});

describe('typed error classes', () => {
  it('ValidationError is an Error and carries name, message and fieldErrors', () => {
    const error = new ValidationError({ name: 'size must be between 1 and 25' });
    expect(error).toBeInstanceOf(Error);
    expect(error.name).toBe('ValidationError');
    expect(error.message).toBe('Validation failed');
    expect(error.fieldErrors.name).toBe('size must be between 1 and 25');
  });

  it('DuplicateEmailError is an Error and defaults its message to "Email taken"', () => {
    const error = new DuplicateEmailError();
    expect(error).toBeInstanceOf(Error);
    expect(error.name).toBe('DuplicateEmailError');
    expect(error.message).toBe('Email taken');
  });

  it('ApiError is an Error and carries name, message and status', () => {
    const error = new ApiError('boom', 503);
    expect(error).toBeInstanceOf(Error);
    expect(error.name).toBe('ApiError');
    expect(error.message).toBe('boom');
    expect(error.status).toBe(503);
  });
});
