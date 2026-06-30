package org.jboss.as.quickstarts.kitchensink.users.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.jboss.as.quickstarts.kitchensink.users.model.Member;
import org.jboss.as.quickstarts.kitchensink.users.repository.MemberRepository;

/**
 * MemberRegistration - registers a new member.
 *
 * <p>Migrated from the legacy EJB {@code @Stateless} bean: container-managed transactions become
 * Spring {@code @Transactional}, persistence is performed through the Spring Data
 * {@link MemberRepository} (its {@code save(...)} replaces the legacy persist call), and the
 * legacy CDI member-event publication is removed (its JSF list-producer consumer was dropped in
 * the Spring Boot migration, so there is no event to fire).</p>
 */
@Service
public class MemberRegistration {

    private static final Logger log = LoggerFactory.getLogger(MemberRegistration.class);

    // Constructor injection (single constructor -> no @Autowired required).
    private final MemberRepository memberRepository;

    public MemberRegistration(MemberRepository memberRepository) {
        this.memberRepository = memberRepository;
    }

    @Transactional
    public void register(Member member) throws Exception {
        log.info("Registering {}", member.getName());
        memberRepository.save(member);
    }
}
