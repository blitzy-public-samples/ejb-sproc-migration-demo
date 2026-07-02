/**
 * MemberList — the read/display side of the Member screen (Use Cases 2 & 3 +
 * loading + the new list-load-failure state).
 *
 * Reproduces the legacy JSF `<h:dataTable value="#{members}">` and the
 * empty-state `<h:panelGroup rendered="#{empty members}">` from
 * kitchensink/src/main/webapp/index.xhtml (L62-93), and adds a loading
 * indicator plus a list-load-failure state required by acceptance criterion #6.
 *
 * It reads server-state via the `useMembers` hook (TanStack Query v5) — it does
 * NOT fetch directly. `useMembers` is the client-side replacement for the CDI
 * `MemberListProducer` `@Produces @Named` list (AAP §0.3.3, §0.4.2).
 *
 * The four state branches are evaluated in a fixed, load-bearing order:
 *   1. isPending -> loading indicator
 *   2. isError   -> "Unable to load members. Try again." (acceptance #6)
 *   3. empty     -> "No registered members."            (acceptance #5)
 *   4. otherwise -> the members table                   (acceptance #4)
 *
 * Rows are rendered in the order the server returns them
 * (`MemberResourceRESTService.listAllMembers()` -> `findAllOrderedByName()`,
 * L68-72); the client performs NO re-sort. The legacy "REST URL" column and
 * footer link are intentionally omitted as legacy chrome (AAP §0.4.2).
 */
import { useMembers } from '../hooks/useMembers';

export function MemberList() {
  const { data, isPending, isError } = useMembers();

  if (isPending) {
    return <p>Loading members…</p>;
  }

  if (isError) {
    return <p>Unable to load members. Try again.</p>;
  }

  const members = data ?? [];

  if (members.length === 0) {
    return <p>No registered members.</p>;
  }

  return (
    <table>
      <thead>
        <tr>
          <th>Id</th>
          <th>Name</th>
          <th>Email</th>
          <th>Phone</th>
        </tr>
      </thead>
      <tbody>
        {members.map((member) => (
          <tr key={member.id}>
            <td>{member.id}</td>
            <td>{member.name}</td>
            <td>{member.email}</td>
            <td>{member.phoneNumber}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}
