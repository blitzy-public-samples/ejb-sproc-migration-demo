import { REST_BASE_URL } from '../config/env';
import { httpGet, httpPost } from './httpClient';

/**
 * Unit tests for the transport layer (`./httpClient`).
 *
 * `httpGet` / `httpPost` are the single, thin seam between the member-mfe
 * remote and the unchanged JAX-RS contract at `/rest/members` (see
 * kitchensink/src/main/java/org/jboss/as/quickstarts/kitchensink/rest/
 * MemberResourceRESTService.java, L68-120). Their entire responsibility is:
 *
 *   1. compose the request URL as `${REST_BASE_URL}${path}` (simple
 *      concatenation — base has no trailing slash, path carries the leading
 *      slash, yielding exactly one separator),
 *   2. set the HTTP method (`GET` / `POST`),
 *   3. send the JSON content-negotiation headers
 *      (`Content-Type: application/json` + `Accept: application/json`),
 *   4. for POST, serialize the body with `JSON.stringify`,
 *   5. return the RAW `fetch` `Response` untouched — no `.ok` / `.json()` /
 *      status inspection or throwing happens here (that anti-corruption logic
 *      lives in membersApi.ts + errors.ts).
 *
 * The suite mocks the global `fetch` so no real network call is made, then
 * asserts each of those responsibilities exactly. It drives `httpClient.ts`
 * to 100% coverage, contributing to the project-wide >=80% statement-coverage
 * gate (AAP 0.7.3 #10).
 *
 * Test environment: Vitest with `globals: true` (see vitest.config.ts), so
 * `describe` / `it` / `expect` / `vi` / `beforeEach` / `afterEach` are
 * available without importing them.
 */

// The exact JSON headers every request in this transport layer must send.
const JSON_HEADERS = {
  'Content-Type': 'application/json',
  Accept: 'application/json',
} as const;

/**
 * A stable sentinel standing in for a `fetch` `Response`. Reusing one shared
 * object lets the "returns the raw Response" tests assert *identity* (`toBe`),
 * which is the strongest possible proof that `httpClient` passes the value
 * straight through with no status handling or re-wrapping. The `ok`/`status`
 * surface also lets those tests confirm the resolved object's properties.
 *
 * The `as unknown as Response` cast is the single controlled cast permitted by
 * the strict-TS rules — a full DOM `Response` is unnecessary here because the
 * transport layer never reads it.
 */
const RESPONSE_SENTINEL = {
  ok: true,
  status: 200,
  json: async () => [],
} as unknown as Response;

/**
 * One `fetch` mock reused across the suite. It is typed with a `fetch`-shaped
 * signature so `fetchMock.mock.calls` is strongly typed (`[url, init]`) without
 * resorting to `any`. The parameters are underscore-prefixed because the mock
 * only records calls — it never reads its arguments — and `noUnusedParameters`
 * (tsconfig) exempts underscore-prefixed names.
 */
const fetchMock = vi.fn(
  (_input: RequestInfo | URL, _init: RequestInit): Promise<Response> =>
    Promise.resolve(RESPONSE_SENTINEL),
);

beforeEach(() => {
  // Clear recorded calls so per-test assertions (toHaveBeenCalledTimes,
  // mock.calls[0]) see only the current test's invocations, then (re)install
  // the mock as the global `fetch`.
  fetchMock.mockClear();
  vi.stubGlobal('fetch', fetchMock);
});

afterEach(() => {
  // Restore the real global `fetch` so each test starts from a clean state and
  // nothing leaks into other test files.
  vi.unstubAllGlobals();
});

describe('httpClient', () => {
  describe('httpGet', () => {
    it('issues a GET to `${REST_BASE_URL}${path}` with the JSON headers', async () => {
      await httpGet('/members');

      expect(fetchMock).toHaveBeenCalledTimes(1);

      const [url, init] = fetchMock.mock.calls[0];
      // URL is asserted both symbolically (robust to a default change) and
      // literally (documents the real, pre-validated value).
      expect(url).toBe(`${REST_BASE_URL}/members`);
      expect(url).toBe('http://localhost:8080/kitchensink/rest/members');
      expect(init).toMatchObject({ method: 'GET', headers: JSON_HEADERS });
    });

    it('returns the raw Response untouched (no status/json handling)', async () => {
      const result = await httpGet('/members');

      // Identity equality proves the exact object fetch resolved is handed
      // back with no re-wrapping.
      expect(result).toBe(RESPONSE_SENTINEL);
      expect(result.ok).toBe(true);
      expect(result.status).toBe(200);
    });

    it('composes a nested path (/members/1) with exactly one separating slash', async () => {
      // Guards the concatenation contract: base without a trailing slash + a
      // leading-slash path must never produce a doubled or missing slash.
      expect(REST_BASE_URL.endsWith('/')).toBe(false);

      await httpGet('/members/1');

      const [url] = fetchMock.mock.calls[0];
      expect(url).toBe(`${REST_BASE_URL}/members/1`);
      expect(url).toBe('http://localhost:8080/kitchensink/rest/members/1');
      expect(url).not.toContain('rest//members');
    });
  });

  describe('httpPost', () => {
    const body = {
      name: 'Jane',
      email: 'jane@example.com',
      phoneNumber: '0123456789',
    };

    it('issues a POST with the JSON headers and a JSON.stringify-ed body', async () => {
      await httpPost('/members', body);

      expect(fetchMock).toHaveBeenCalledTimes(1);

      const [url, init] = fetchMock.mock.calls[0];
      expect(url).toBe(`${REST_BASE_URL}/members`);
      expect(init).toMatchObject({ method: 'POST', headers: JSON_HEADERS });
      // The body is the serialized payload verbatim — no envelope, no mutation.
      expect(init.body).toBe(JSON.stringify(body));
    });

    it('returns the raw Response untouched (no status/json handling)', async () => {
      const result = await httpPost('/members', body);

      expect(result).toBe(RESPONSE_SENTINEL);
      expect(result.status).toBe(200);
    });

    it('serializes non-plain bodies (e.g. arrays) faithfully via JSON.stringify', async () => {
      const arrayBody = [{ id: 1 }, { id: 2 }];

      await httpPost('/members', arrayBody);

      const [, init] = fetchMock.mock.calls[0];
      expect(init.body).toBe(JSON.stringify(arrayBody));
      expect(init.method).toBe('POST');
    });
  });

  it('sends both JSON headers (Content-Type + Accept) on GET and POST alike', async () => {
    await httpGet('/members');
    await httpPost('/members', { sample: 'payload' });

    expect(fetchMock).toHaveBeenCalledTimes(2);
    for (const [, init] of fetchMock.mock.calls) {
      expect(init.headers).toMatchObject(JSON_HEADERS);
    }
  });
});
