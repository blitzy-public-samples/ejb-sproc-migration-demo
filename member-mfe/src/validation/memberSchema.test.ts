import { memberSchema } from './memberSchema';

/**
 * Unit tests for the client-side Zod schema that mirrors the server-side
 * Bean Validation constraints on Member.java (L30-44):
 *   name        -> @NotNull @Size(1,25) @Pattern("[^0-9]*", "Must not contain numbers")
 *   email       -> @NotNull @NotEmpty @Email
 *   phoneNumber -> @NotNull @Size(10,12) @Digits(fraction=0, integer=12)
 *
 * Validates acceptance criterion #2 (registration validation error) rules and
 * contributes to the >=80% statement-coverage gate.
 *
 * `describe`, `it`, and `expect` are Vitest globals (vitest.config.ts: globals: true).
 */
describe('memberSchema', () => {
  const validInput = {
    name: 'Jane Doe',
    email: 'jane@example.com',
    phoneNumber: '1234567890',
  };

  it('accepts a fully valid member', () => {
    const result = memberSchema.safeParse(validInput);
    expect(result.success).toBe(true);
  });

  describe('name', () => {
    it('rejects a name containing numbers with the server-mirrored message', () => {
      const result = memberSchema.safeParse({ ...validInput, name: 'John123' });
      expect(result.success).toBe(false);
      if (!result.success) {
        expect(result.error.flatten().fieldErrors.name).toContain(
          'Must not contain numbers',
        );
      }
    });

    it('rejects an empty name (min length 1)', () => {
      const result = memberSchema.safeParse({ ...validInput, name: '' });
      expect(result.success).toBe(false);
    });

    it('rejects a name longer than 25 characters (max length)', () => {
      const result = memberSchema.safeParse({
        ...validInput,
        name: 'x'.repeat(26),
      });
      expect(result.success).toBe(false);
    });

    it('accepts a 25-character name (max boundary)', () => {
      const result = memberSchema.safeParse({
        ...validInput,
        name: 'x'.repeat(25),
      });
      expect(result.success).toBe(true);
    });
  });

  describe('email', () => {
    it('rejects a malformed email address', () => {
      const result = memberSchema.safeParse({
        ...validInput,
        email: 'not-an-email',
      });
      expect(result.success).toBe(false);
    });

    it('rejects an empty email', () => {
      const result = memberSchema.safeParse({ ...validInput, email: '' });
      expect(result.success).toBe(false);
    });
  });

  describe('phoneNumber', () => {
    it('rejects a phone number that is too short (< 10 digits)', () => {
      const result = memberSchema.safeParse({
        ...validInput,
        phoneNumber: '123',
      });
      expect(result.success).toBe(false);
    });

    it('rejects a phone number that is too long (> 12 digits)', () => {
      const result = memberSchema.safeParse({
        ...validInput,
        phoneNumber: '1234567890123',
      });
      expect(result.success).toBe(false);
    });

    it('rejects a phone number containing non-digit characters', () => {
      const result = memberSchema.safeParse({
        ...validInput,
        phoneNumber: '12a4567890',
      });
      expect(result.success).toBe(false);
    });

    it('accepts a 12-digit phone number (max boundary)', () => {
      const result = memberSchema.safeParse({
        ...validInput,
        phoneNumber: '123456789012',
      });
      expect(result.success).toBe(true);
    });
  });
});
