/**
 * MemberPage — thin container that composes the Member screen.
 *
 * Reproduces the overall layout of the legacy JSF page
 * kitchensink/src/main/webapp/index.xhtml: the registration form, followed by
 * the "Members" heading and the members list. This is the "container" half of
 * the container/presentational split (AAP §0.3.3): `MemberPage` orchestrates
 * layout; `MemberRegistrationForm` and `MemberList` render.
 *
 * It is rendered by the exposed `App.tsx` inside its `QueryProvider`, so this
 * component intentionally supplies NO provider, data-fetching, or state — it is
 * pure composition. (`MemberRegistrationForm` renders its own
 * "Member Registration" heading, so the page shows two headings —
 * "Member Registration" then "Members" — matching the JSF page.)
 */
import { MemberRegistrationForm } from './MemberRegistrationForm';
import { MemberList } from './MemberList';

export function MemberPage() {
  return (
    <div>
      <MemberRegistrationForm />
      <h2>Members</h2>
      <MemberList />
    </div>
  );
}
