/**
 * Unit tests for the typed member API (`./membersApi`).
 *
 * These tests exercise the two functions the TanStack Query hooks consume —
 * `getMembers()` and `createMember()` — against the immutable `/rest/members`
 * contract implemented by the unchanged JAX-RS
 * `MemberResourceRESTService` (kitchensink/.../rest/MemberResourceRESTService.java,
 * L68-120).
 *
 * Strategy: the direct transport collaborator (`./httpClient`) is mocked so we
 * can drive `httpGet`/`httpPost` to any `Response` we like, while the
 * anti-corruption adapter (`./errors`) runs FOR REAL — this gives genuine
 * integration coverage of the status-code -> typed-error mapping through
 * `membersApi`. Every branch of both functions (OK / non-OK) is covered.
 *
 * Acceptance criteria touched: #1 (registration success -> 200 empty body),
 * #2 (400 field map -> per-field errors), #3 (409 -> duplicate email),
 * #4 (list rendered in server order — no client re-sort), #5 (empty list),
 * #6 (list-load failure -> ApiError). See AAP 0.7.3.
 *
 * Test environment: Vitest (`globals: true` in vitest.config.ts). Imports from
 * 'vitest' are explicit here to mirror the sibling httpClient.test.ts style.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { httpGet, httpPost } from './httpClient';
import { ApiError, DuplicateEmailError, ValidationError } from './errors';
import type { Member, NewMemberInput } from '../types/member';
import { createMember, getMembers } from './membersApi';

// Replace the transport module with mock functions. membersApi keeps the REAL
// type declarations from './httpClient' (vi.mock only affects runtime), so
// strict-mode type-checking is unaffected. The real './errors' is NOT mocked.
vi.mock('./httpClient', () => ({
  httpGet: vi.fn(),
  httpPost: vi.fn(),
}));

const mockedHttpGet = vi.mocked(httpGet);
const mockedHttpPost = vi.mocked(httpPost);

/**
 * Builds a minimal `Response`-like stub. A real DOM `Response` enforces
 * one-shot body consumption which is awkward for these tests, so we construct
 * only the surface (`ok`, `status`, `json`) that the code under test (and the
 * real error adapter) touch, and cast it through `unknown`.
 */
function fakeResponse(fields: { ok?: boolean; status: number; json: unknown }): Response {
  return {
    ok: fields.ok ?? false,
    status: fields.status,
    json: fields.json,
  } as unknown as Response;
}

/**
 * Awaits a promise expected to reject and returns the rejection value as
 * `unknown` for type-safe `instanceof` narrowing. Throws loudly if the promise
 * unexpectedly resolves.
 */
async function captureRejection(promise: Promise<unknown>): Promise<unknown> {
  try {
    await promise;
  } catch (error) {
    return error;
  }
  throw new Error('Expected the promise to reject, but it resolved.');
}

// A valid registration payload reused across the createMember tests.
const VALID_INPUT: NewMemberInput = {
  name: 'Jane Doe',
  email: 'jane@example.com',
  phoneNumber: '2125551234',
};

beforeEach(() => {
  // Clear call history between tests; each test sets its own resolved value.
  vi.clearAllMocks();
});

describe('getMembers', () => {
  it('GETs /members and returns the parsed Member[] on 200, preserving server order (no client re-sort)', async () => {
    // The server returns the list ALREADY ordered by name; the client must
    // pass it through UNCHANGED. We feed a deliberately NON-alphabetical array
    // and assert the exact same order comes back out (proving no re-sort).
    const serverOrder: Member[] = [
      { id: 10, name: 'Zoe Zephyr', email: 'zoe@example.com', phoneNumber: '2125559999' },
      { id: 11, name: 'Ada Lovelace', email: 'ada@example.com', phoneNumber: '2125551234' },
    ];
    const jsonSpy = vi.fn().mockResolvedValue(serverOrder);
    mockedHttpGet.mockResolvedValue(fakeResponse({ ok: true, status: 200, json: jsonSpy }));

    const result = await getMembers();

    expect(mockedHttpGet).toHaveBeenCalledTimes(1);
    expect(mockedHttpGet).toHaveBeenCalledWith('/members');
    expect(result).toEqual(serverOrder);
    expect(result.map((m) => m.name)).toEqual(['Zoe Zephyr', 'Ada Lovelace']);
    expect(jsonSpy).toHaveBeenCalledTimes(1);
  });

  it('returns an empty array on 200 with [] (drives the empty-state, acceptance #5)', async () => {
    mockedHttpGet.mockResolvedValue(
      fakeResponse({ ok: true, status: 200, json: vi.fn().mockResolvedValue([]) }),
    );

    const result = await getMembers();

    expect(result).toEqual([]);
  });

  it('tolerates the extended kitchensink Member fields (tier/totalSpend/tierUpdatedAt)', async () => {
    const extended: Member[] = [
      {
        id: 1,
        name: 'Grace Hopper',
        email: 'grace@example.com',
        phoneNumber: '2125555678',
        tier: 'GOLD',
        totalSpend: 1234.5,
        tierUpdatedAt: '2026-01-01T00:00:00Z',
      },
    ];
    mockedHttpGet.mockResolvedValue(
      fakeResponse({ ok: true, status: 200, json: vi.fn().mockResolvedValue(extended) }),
    );

    const result = await getMembers();

    expect(result).toEqual(extended);
    expect(result[0].tier).toBe('GOLD');
    expect(result[0].totalSpend).toBe(1234.5);
  });

  it('throws (via the real error adapter) an ApiError on a non-OK response — list-load failure, acceptance #6', async () => {
    mockedHttpGet.mockResolvedValue(
      fakeResponse({ ok: false, status: 500, json: async () => ({}) }),
    );

    const error = await captureRejection(getMembers());

    expect(error).toBeInstanceOf(ApiError);
    if (error instanceof ApiError) {
      expect(error.status).toBe(500);
      expect(error.message).toBe('Request failed with status 500');
    }
  });
});

describe('createMember', () => {
  it('POSTs /members with the input and resolves to undefined on a 200 EMPTY body WITHOUT calling res.json()', async () => {
    // The `json` spy must NEVER be invoked: success is an empty 200 body and
    // parsing it would throw. This is the core contract of createMember.
    const jsonSpy = vi.fn();
    mockedHttpPost.mockResolvedValue(fakeResponse({ ok: true, status: 200, json: jsonSpy }));

    const result = await createMember(VALID_INPUT);

    expect(result).toBeUndefined();
    expect(mockedHttpPost).toHaveBeenCalledTimes(1);
    expect(mockedHttpPost).toHaveBeenCalledWith('/members', VALID_INPUT);
    // KEY assertion: the empty 200 body is never parsed.
    expect(jsonSpy).not.toHaveBeenCalled();
  });

  it('throws a ValidationError with the server field map on 400 (acceptance #2)', async () => {
    mockedHttpPost.mockResolvedValue(
      fakeResponse({
        ok: false,
        status: 400,
        json: async () => ({
          name: 'size must be between 1 and 25',
          email: 'must be a well-formed email address',
        }),
      }),
    );

    const error = await captureRejection(createMember(VALID_INPUT));

    expect(error).toBeInstanceOf(ValidationError);
    if (error instanceof ValidationError) {
      expect(error.fieldErrors.name).toBe('size must be between 1 and 25');
      expect(error.fieldErrors.email).toBe('must be a well-formed email address');
      expect(error.fieldErrors.phoneNumber).toBeUndefined();
    }
    expect(mockedHttpPost).toHaveBeenCalledWith('/members', VALID_INPUT);
  });

  it('throws an ApiError(400) on a generic { error } 400 body', async () => {
    mockedHttpPost.mockResolvedValue(
      fakeResponse({
        ok: false,
        status: 400,
        json: async () => ({ error: 'Unexpected server failure' }),
      }),
    );

    const error = await captureRejection(createMember(VALID_INPUT));

    expect(error).toBeInstanceOf(ApiError);
    if (error instanceof ApiError) {
      expect(error.status).toBe(400);
      expect(error.message).toBe('Unexpected server failure');
    }
  });

  it('throws a DuplicateEmailError on 409 (acceptance #3)', async () => {
    mockedHttpPost.mockResolvedValue(
      fakeResponse({ ok: false, status: 409, json: async () => ({ email: 'Email taken' }) }),
    );

    const error = await captureRejection(createMember(VALID_INPUT));

    expect(error).toBeInstanceOf(DuplicateEmailError);
    if (error instanceof DuplicateEmailError) {
      expect(error.message).toBe('Email taken');
    }
  });
});
