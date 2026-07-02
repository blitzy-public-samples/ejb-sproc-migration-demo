import { afterEach, describe, expect, it, vi } from 'vitest';

import { REST_BASE_URL } from '../config/env';
import { httpGet, httpPost } from './httpClient';

/**
 * Unit tests for the transport layer (httpClient.ts).
 *
 * These tests stub the global `fetch` so no real network request is made,
 * then assert the exact URL composition, HTTP method, JSON headers and
 * request body that the transport module produces. They also verify that the
 * RAW `Response` is returned unchanged (no `.ok`/`.json()` handling and no
 * throwing happens in this layer — that responsibility lives in membersApi.ts
 * + errors.ts).
 */
describe('httpClient', () => {
  // The exact JSON headers both requests must send.
  const EXPECTED_HEADERS = {
    'Content-Type': 'application/json',
    Accept: 'application/json',
  };

  afterEach(() => {
    // Remove the `fetch` stub so each test starts from a clean global state.
    vi.unstubAllGlobals();
  });

  it('httpGet issues a GET to `${REST_BASE_URL}${path}` with JSON headers and returns the raw Response', async () => {
    // A sentinel object stands in for the Response so we can assert identity
    // equality (proving the raw value is returned untouched).
    const sentinel = { status: 200 } as unknown as Response;
    const fetchMock = vi.fn().mockResolvedValue(sentinel);
    vi.stubGlobal('fetch', fetchMock);

    const result = await httpGet('/members');

    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(fetchMock).toHaveBeenCalledWith(`${REST_BASE_URL}/members`, {
      method: 'GET',
      headers: EXPECTED_HEADERS,
    });
    // The raw Response is passed straight through — no status handling here.
    expect(result).toBe(sentinel);
  });

  it('httpPost issues a POST with JSON headers, a JSON.stringify-ed body, and returns the raw Response', async () => {
    const sentinel = { status: 200 } as unknown as Response;
    const fetchMock = vi.fn().mockResolvedValue(sentinel);
    vi.stubGlobal('fetch', fetchMock);

    const body = {
      name: 'Jane Doe',
      email: 'jane@example.com',
      phoneNumber: '1234567890',
    };

    const result = await httpPost('/members', body);

    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(fetchMock).toHaveBeenCalledWith(`${REST_BASE_URL}/members`, {
      method: 'POST',
      headers: EXPECTED_HEADERS,
      body: JSON.stringify(body),
    });
    expect(result).toBe(sentinel);
  });

  it('composes the URL by simple concatenation with no extra or missing slash', async () => {
    const fetchMock = vi.fn().mockResolvedValue({} as unknown as Response);
    vi.stubGlobal('fetch', fetchMock);

    // Base URL default resolves without a trailing slash; callers pass a
    // leading-slash path, yielding exactly one slash between the two.
    expect(REST_BASE_URL).toBe('http://localhost:8080/kitchensink/rest');
    expect(REST_BASE_URL.endsWith('/')).toBe(false);

    await httpGet('/members');

    const [calledUrl] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(calledUrl).toBe('http://localhost:8080/kitchensink/rest/members');
    // No accidental double slash between base and path.
    expect(calledUrl).not.toContain('rest//members');
  });

  it('serializes different POST bodies faithfully via JSON.stringify', async () => {
    const fetchMock = vi.fn().mockResolvedValue({} as unknown as Response);
    vi.stubGlobal('fetch', fetchMock);

    const arrayBody = [{ id: 1 }, { id: 2 }];
    await httpPost('/members', arrayBody);

    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(init.body).toBe(JSON.stringify(arrayBody));
    expect(init.method).toBe('POST');
  });
});
