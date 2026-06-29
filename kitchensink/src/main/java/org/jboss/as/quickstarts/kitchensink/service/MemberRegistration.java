/*
 * JBoss, Home of Professional Open Source
 * Copyright 2015, Red Hat, Inc. and/or its affiliates, and individual
 * contributors by the @authors tag. See the copyright.txt in the
 * distribution for a full listing of individual contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jboss.as.quickstarts.kitchensink.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.jboss.as.quickstarts.kitchensink.data.MemberRepository;
import org.jboss.as.quickstarts.kitchensink.model.Member;

/**
 * MemberRegistration — persists a new {@link Member}.
 *
 * <p>MIGRATION (JBoss EAP 8 / Jakarta EE 10 -&gt; Spring Boot 3.x): formerly an EJB
 * {@code @Stateless} bean that injected an {@code EntityManager} and a JUL {@code Logger}, persisted
 * via {@code em.persist(member)}, and fired a CDI {@code Event<Member>}. It is now a Spring
 * {@code @Service} with declarative {@code @Transactional} demarcation (replacing the EJB's implicit
 * container-managed transaction), a private SLF4J logger (replacing the CDI-injected JUL logger),
 * and persistence through the Spring Data {@link MemberRepository}.</p>
 *
 * <p>The CDI {@code Event<Member>} fire is removed: its sole observer was the JSF
 * {@code MemberListProducer}, which is retired as part of the JSF/CDI plumbing removal. There is no
 * in-scope Spring equivalent and no remaining listener, so the event is dropped per the migration's
 * minimal-change directive.</p>
 */
@Service
public class MemberRegistration {

    private static final Logger log = LoggerFactory.getLogger(MemberRegistration.class);

    private final MemberRepository memberRepository;

    public MemberRegistration(MemberRepository memberRepository) {
        this.memberRepository = memberRepository;
    }

    /**
     * Registers (persists) a new member. After this call returns, the member's generated identifier
     * is populated on the supplied instance.
     *
     * @param member the member to register
     */
    @Transactional
    public void register(Member member) {
        log.info("Registering {}", member.getName());
        memberRepository.save(member);
    }
}
