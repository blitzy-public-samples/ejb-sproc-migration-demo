export interface Member {
  id: number;
  name: string;
  email: string;
  phoneNumber: string;
  // Extended kitchensink variant fields — tolerated, optional; must not break parsing.
  tier?: string;
  totalSpend?: number;
  tierUpdatedAt?: string;
}

export interface NewMemberInput {
  name: string;
  email: string;
  phoneNumber: string;
}
