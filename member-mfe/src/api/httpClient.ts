import { REST_BASE_URL } from '../config/env';

const JSON_HEADERS: Readonly<Record<string, string>> = {
  'Content-Type': 'application/json',
  Accept: 'application/json',
};

export function httpGet(path: string): Promise<Response> {
  return fetch(`${REST_BASE_URL}${path}`, {
    method: 'GET',
    headers: { ...JSON_HEADERS },
  });
}

export function httpPost(path: string, body: unknown): Promise<Response> {
  return fetch(`${REST_BASE_URL}${path}`, {
    method: 'POST',
    headers: { ...JSON_HEADERS },
    body: JSON.stringify(body),
  });
}
