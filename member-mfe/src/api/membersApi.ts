/**
 * Typed API calls against the immutable `/rest/members` contract.
 *
 * This module is the single home for the two member operations the TanStack
 * Query hooks consume: `getMembers()` (list) and `createMember(input)`
 * (register). It composes three collaborators:
 *   - the transport (`./httpClient`) — owns the base URL, JSON headers and
 *     `fetch`; returns the raw `Response`;
 *   - the anti-corruption adapter (`./errors`) — translates non-OK responses
 *     into typed client errors and ALWAYS throws;
 *   - the domain types (`../types/member`) — the `Member` shape (tolerant of
 *     the extended kitchensink `tier`/`totalSpend`/`tierUpdatedAt` fields) and
 *     the `NewMemberInput` registration payload.
 *
 * The REST contract is consumed EXACTLY as-is — no path, verb, status code or
 * JSON shape is altered here. Behavioral source of truth (never modified):
 * kitchensink/src/main/java/org/jboss/as/quickstarts/kitchensink/rest/
 * MemberResourceRESTService.java (L68-120):
 *   - GET  /members -> 200 with a JSON `Member[]` ALREADY ordered by name
 *     server-side (do NOT re-sort on the client). Any non-OK drives the
 *     list-load-failure state (acceptance #6).
 *   - POST /members -> SUCCESS is HTTP 200 with an EMPTY body (`Response.ok()`,
 *     Java L103) — so `createMember` returns `void` and MUST NOT call
 *     `res.json()`. Non-OK yields a typed error: `ValidationError` for the 400
 *     Bean Validation field map, `ApiError` for a generic 400 `{ error }`, and
 *     `DuplicateEmailError` for the 409 duplicate email.
 */
import { httpGet, httpPost } from './httpClient';
import { normalizeErrorResponse } from './errors';
import type { Member, NewMemberInput } from '../types/member';

export async function getMembers(): Promise<Member[]> {
  const res = await httpGet('/members');
  if (!res.ok) {
    await normalizeErrorResponse(res);
  }
  return (await res.json()) as Member[];
}

export async function createMember(input: NewMemberInput): Promise<void> {
  const res = await httpPost('/members', input);
  if (!res.ok) {
    await normalizeErrorResponse(res);
  }
  // SUCCESS is HTTP 200 with an EMPTY body — do NOT call res.json() (it would throw).
}
