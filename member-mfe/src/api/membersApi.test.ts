/**
 * Unit tests for the typed member API client (`./membersApi`).
 *
 * These tests exercise the two functions the TanStack Query hooks consume —
 * `getMembers()` and `createMember()` — against the immutable `/rest/members`
 * contract implemented by the unchanged JAX-RS `MemberResourceRESTService`
 * (kitchensink/src/main/java/org/jboss/as/quickstarts/kitchensink/rest/
 * MemberResourceRESTService.java, L68-120):
 *
 *   - GET  /members -> 200 with a JSON `Member[]` ALREADY ordered by name
 *     server-side (Java L70-72). The client must NOT re-sort. Any non-OK drives
 *     the list-load-failure state (acceptance #6).
 *   - POST /members -> SUCCESS is HTTP 200 with an EMPTY body (`Response.ok()`,
 *     Java L103) — so `createMember` resolves `undefined` and MUST NOT call
 *     `res.json()`. Non-OK yields a typed error: `ValidationError` for the 400
 *     Bean Validation field map (Java L104-106, L157-167), `ApiError` for a
 *     generic 400 `{ error }` (Java L112-116), and `DuplicateEmailError` for the
 *     409 duplicate email (Java L107-111).
 *
 * Strategy — mock the GLOBAL `fetch`. This is the preferred approach because it
 * drives `getMembers`/`createMember` end-to-end through the REAL transport
 * (`./httpClient`) and the REAL anti-corruption adapter (`./errors`), giving
 * genuine integrated coverage of the whole `api/` request path (URL
 * composition, JSON headers, `JSON.stringify` body, status -> typed-error
 * mapping) rather than stubbing the seam directly. Nothing about the wire
 * format is faked beyond the `Response` surface the code actually reads.
 *
 * Acceptance criteria touched: #1 (registration success -> 200 empty body),
 * #2 (400 field map -> per-field errors), #3 (409 -> duplicate email),
 * #4 (list rendered in server order — no client re-sort), #5 (empty list),
 * #6 (list-load failure -> ApiError). See AAP 0.7.3.
 *
 * Test environment: Vitest with `globals: true` (see vitest.config.ts), so
 * `describe` / `it` / `expect` / `vi` / `beforeEach` / `afterEach` are available
 * without importing them — mirroring the sibling httpClient.test.ts style.
 */
import { getMembers, createMember } from './membersApi';
import { ApiError, DuplicateEmailError, ValidationError } from './errors';
import type { Member, NewMemberInput } from '../types/member';

/**
 * The single `fetch` mock reused across the suite. It is typed with a
 * `fetch`-shaped signature so `fetchMock.mock.calls` is strongly typed
 * (`[input, init]`) without resorting to `any`. It carries no default
 * implementation — every test installs its own resolved `Response` via
 * `mockResolvedValue`, and `beforeEach` resets it to a clean slate.
 */
const fetchMock = vi.fn<(input: RequestInfo | URL, init: RequestInit) => Promise<Response>>();

beforeEach(() => {
  // Full reset: clears recorded calls AND any per-test resolved value so tests
  // never leak state into one another, then (re)install as the global `fetch`.
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
});

afterEach(() => {
  // Restore the real global `fetch` so nothing leaks into other test files.
  vi.unstubAllGlobals();
});

/**
 * Builds a minimal OK (`200`) `Response`-like stub. A real DOM `Response`
 * enforces one-shot body consumption which is awkward here, so we construct
 * only the surface (`ok`, `status`, `json`) that the code under test touches
 * and cast it through `unknown`. This `as unknown as Response` cast is the
 * single controlled cast permitted by the strict-TS rules.
 */
function ok(json: () => Promise<unknown>): Response {
  return { ok: true, status: 200, json } as unknown as Response;
}

/**
 * Builds a minimal non-OK `Response`-like stub for the given status. The real
 * `normalizeErrorResponse` (`./errors`) reads `res.json()` to shape the typed
 * error, so `json` is supplied per test.
 */
function fail(status: number, json: () => Promise<unknown>): Response {
  return { ok: false, status, json } as unknown as Response;
}

/**
 * Awaits a promise expected to reject and returns the rejection value as
 * `unknown` for type-safe `instanceof` narrowing. Fails loudly (throws) if the
 * promise unexpectedly resolves — the non-OK paths must ALWAYS throw.
 */
async function captureRejection(promise: Promise<unknown>): Promise<unknown> {
  try {
    await promise;
  } catch (error) {
    return error;
  }
  throw new Error('Expected the promise to reject, but it resolved.');
}

// A valid registration payload reused across the createMember tests. camelCase
// `phoneNumber` matches NewMemberInput and the JSON the server consumes.
const VALID_INPUT: NewMemberInput = {
  name: 'Jane Doe',
  email: 'jane@example.com',
  phoneNumber: '2125551234',
};

describe('getMembers', () => {
  it('GETs /members and returns the parsed Member[] on 200, preserving server order (no client re-sort)', async () => {
    // The server returns the list ALREADY ordered by name; the client must pass
    // it through UNCHANGED. We feed a deliberately NON-alphabetical array and
    // assert the exact same order comes back out, proving there is no re-sort.
    const serverOrder: Member[] = [
      { id: 10, name: 'Zoe Zephyr', email: 'zoe@example.com', phoneNumber: '2125559999' },
      { id: 11, name: 'Ada Lovelace', email: 'ada@example.com', phoneNumber: '2125551234' },
    ];
    fetchMock.mockResolvedValue(ok(async () => serverOrder));

    const result = await getMembers();

    // Result is the parsed body, deep-equal and in the SAME (server) order.
    expect(result).toEqual(serverOrder);
    expect(result.map((member) => member.name)).toEqual(['Zoe Zephyr', 'Ada Lovelace']);

    // Exactly one GET to a URL ending `/members`.
    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, init] = fetchMock.mock.calls[0];
    expect(String(url).endsWith('/members')).toBe(true);
    expect(init.method).toBe('GET');
  });

  it('returns an empty array on a 200 empty list (drives the empty-state, acceptance #5)', async () => {
    fetchMock.mockResolvedValue(ok(async () => []));

    const result = await getMembers();

    expect(result).toEqual([]);
    expect(result).toHaveLength(0);
  });

  it('tolerates the extended kitchensink Member fields alongside a plain entry (no runtime filtering)', async () => {
    // One entry carries the extended kitchensink fields (tier/totalSpend/
    // tierUpdatedAt); the other is a plain member. Both must survive intact.
    const members: Member[] = [
      {
        id: 1,
        name: 'Grace Hopper',
        email: 'grace@example.com',
        phoneNumber: '2125555678',
        tier: 'GOLD',
        totalSpend: 1234.5,
        tierUpdatedAt: '2026-01-01T00:00:00Z',
      },
      { id: 2, name: 'Alan Turing', email: 'alan@example.com', phoneNumber: '2125550000' },
    ];
    fetchMock.mockResolvedValue(ok(async () => members));

    const result = await getMembers();

    expect(result).toEqual(members);
    // Extended optional fields are preserved verbatim on the first entry...
    expect(result[0].tier).toBe('GOLD');
    expect(result[0].totalSpend).toBe(1234.5);
    expect(result[0].tierUpdatedAt).toBe('2026-01-01T00:00:00Z');
    // ...and remain absent on the plain entry (not fabricated).
    expect(result[1].tier).toBeUndefined();
    expect(result[1].totalSpend).toBeUndefined();
  });

  it('throws an ApiError (status 500) on a non-OK response — list-load failure, acceptance #6', async () => {
    fetchMock.mockResolvedValue(fail(500, async () => ({})));

    await expect(getMembers()).rejects.toBeInstanceOf(ApiError);

    const error = await captureRejection(getMembers());
    expect(error).toBeInstanceOf(ApiError);
    if (error instanceof ApiError) {
      expect(error.status).toBe(500);
      expect(error.message).toBe('Request failed with status 500');
    }
  });
});

describe('createMember', () => {
  it('resolves to undefined on a 200 EMPTY body WITHOUT calling res.json()', async () => {
    // Success is an empty 200 body (Java Response.ok()); parsing it would throw.
    // The spy therefore throws if ever invoked AND we assert it was not called —
    // this is the core parity behavior of createMember.
    const jsonSpy = vi.fn(async () => {
      throw new Error('res.json() must not be called on a 200 empty body');
    });
    fetchMock.mockResolvedValue(ok(jsonSpy));

    const result = await createMember(VALID_INPUT);

    expect(result).toBeUndefined();
    expect(jsonSpy).not.toHaveBeenCalled();
  });

  it('POSTs to /members with method POST and a JSON.stringify-ed body', async () => {
    fetchMock.mockResolvedValue(ok(async () => undefined));

    await createMember(VALID_INPUT);

    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, init] = fetchMock.mock.calls[0];
    expect(String(url).endsWith('/members')).toBe(true);
    expect(init.method).toBe('POST');
    // The body is the serialized payload verbatim — no envelope, no mutation.
    expect(init.body).toBe(JSON.stringify(VALID_INPUT));
  });

  it('throws a ValidationError carrying the server field map on 400 (acceptance #2)', async () => {
    fetchMock.mockResolvedValue(
      fail(400, async () => ({
        name: 'size must be between 1 and 25',
        email: 'must be a well-formed email address',
      })),
    );

    await expect(createMember(VALID_INPUT)).rejects.toBeInstanceOf(ValidationError);

    const error = await captureRejection(createMember(VALID_INPUT));
    expect(error).toBeInstanceOf(ValidationError);
    if (error instanceof ValidationError) {
      expect(error.fieldErrors.name).toBe('size must be between 1 and 25');
      expect(error.fieldErrors.email).toBe('must be a well-formed email address');
      expect(error.fieldErrors.phoneNumber).toBeUndefined();
    }
  });

  it('throws an ApiError(400) on a generic { error } 400 body', async () => {
    // Mirrors the Java generic-exception branch (L112-116): { error: message }.
    fetchMock.mockResolvedValue(fail(400, async () => ({ error: 'Unique Email Violation' })));

    await expect(createMember(VALID_INPUT)).rejects.toBeInstanceOf(ApiError);

    const error = await captureRejection(createMember(VALID_INPUT));
    expect(error).toBeInstanceOf(ApiError);
    if (error instanceof ApiError) {
      expect(error.status).toBe(400);
      expect(error.message).toBe('Unique Email Violation');
    }
  });

  it('throws a DuplicateEmailError on 409 (acceptance #3)', async () => {
    // Mirrors the Java ValidationException branch (L107-111): { email: "Email taken" }.
    fetchMock.mockResolvedValue(fail(409, async () => ({ email: 'Email taken' })));

    await expect(createMember(VALID_INPUT)).rejects.toBeInstanceOf(DuplicateEmailError);

    const error = await captureRejection(createMember(VALID_INPUT));
    expect(error).toBeInstanceOf(DuplicateEmailError);
    if (error instanceof DuplicateEmailError) {
      expect(error.message).toBe('Email taken');
    }
  });
});
