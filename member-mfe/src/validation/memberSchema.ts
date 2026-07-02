import { z } from 'zod';

export const memberSchema = z.object({
  name: z
    .string()
    .min(1, 'Name is required')
    .max(25, 'Name must be at most 25 characters')
    .regex(/^[^0-9]*$/, 'Must not contain numbers'),
  email: z
    .string()
    .min(1, 'Email is required')
    .email('Invalid email address'),
  phoneNumber: z
    .string()
    .min(10, 'Phone number must be between 10 and 12 digits')
    .max(12, 'Phone number must be between 10 and 12 digits')
    .regex(/^[0-9]+$/, 'Phone number must contain only digits'),
});

export type MemberFormValues = z.infer<typeof memberSchema>;
