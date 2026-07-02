import { useQuery } from '@tanstack/react-query';
import { getMembers } from '../api/membersApi';
import type { Member } from '../types/member';

export function useMembers() {
  return useQuery<Member[]>({
    queryKey: ['members'],
    queryFn: getMembers,
  });
}
