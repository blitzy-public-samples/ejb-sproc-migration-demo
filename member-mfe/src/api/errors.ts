export interface FieldErrors {
  name?: string;
  email?: string;
  phoneNumber?: string;
}

export class ValidationError extends Error {
  readonly fieldErrors: FieldErrors;
  constructor(fieldErrors: FieldErrors) {
    super('Validation failed');
    this.name = 'ValidationError';
    this.fieldErrors = fieldErrors;
  }
}

export class DuplicateEmailError extends Error {
  constructor(message = 'Email taken') {
    super(message);
    this.name = 'DuplicateEmailError';
  }
}

export class ApiError extends Error {
  readonly status: number;
  constructor(message: string, status: number) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
  }
}

const FIELD_KEYS = ['name', 'email', 'phoneNumber'] as const;

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

/** Reads a non-OK Response body and throws the appropriate typed error. Always throws. */
export async function normalizeErrorResponse(res: Response): Promise<never> {
  let body: unknown;
  try {
    body = await res.json();
  } catch {
    body = undefined;
  }

  // 409 -> duplicate email: { "email": "Email taken" }
  if (res.status === 409) {
    const message =
      isRecord(body) && typeof body.email === 'string' ? body.email : 'Email taken';
    throw new DuplicateEmailError(message);
  }

  // 400 -> generic { "error": msg } OR field-map { name?/email?/phoneNumber? }
  if (res.status === 400 && isRecord(body)) {
    if (typeof body.error === 'string') {
      throw new ApiError(body.error, 400);
    }
    const fieldErrors: FieldErrors = {};
    for (const key of FIELD_KEYS) {
      const value = body[key];
      if (typeof value === 'string') {
        fieldErrors[key] = value;
      }
    }
    if (Object.keys(fieldErrors).length > 0) {
      throw new ValidationError(fieldErrors);
    }
  }

  // Fallback for any other non-OK status (e.g. list GET failures).
  throw new ApiError(`Request failed with status ${res.status}`, res.status);
}
