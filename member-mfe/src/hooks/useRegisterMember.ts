import { useMutation, useQueryClient } from '@tanstack/react-query';
import { createMember } from '../api/membersApi';
import type { NewMemberInput } from '../types/member';

export function useRegisterMember() {
  const queryClient = useQueryClient();
  return useMutation<void, Error, NewMemberInput>({
    mutationFn: createMember,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['members'] });
    },
  });
}
