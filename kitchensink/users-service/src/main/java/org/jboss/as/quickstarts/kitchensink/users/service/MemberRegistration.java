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
package org.jboss.as.quickstarts.kitchensink.users.service;

import org.jboss.as.quickstarts.kitchensink.users.model.Member;
import org.jboss.as.quickstarts.kitchensink.users.repository.MemberRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Member-onboarding service for users-service.
 *
 * <p>Converted from the monolith's {@code @Stateless} EJB: container-managed transactions become
 * Spring {@link Transactional}; the injected {@code EntityManager.persist(...)} becomes
 * {@link MemberRepository#save(Object)}; and CDI field injection becomes constructor injection.</p>
 *
 * <p>The monolith fired a CDI {@code Event<Member>} after persistence to refresh the JSF
 * {@code MemberListProducer}. That producer is dropped in the migration (AAP &sect;0.2.2), leaving no
 * remaining observer, so the event is dropped as the minimal-change choice (AAP &sect;0.4.1 permits
 * "ApplicationEventPublisher ... or drop").</p>
 */
@Service
public class MemberRegistration {

    private static final Logger log = LoggerFactory.getLogger(MemberRegistration.class);

    private final MemberRepository memberRepository;

    public MemberRegistration(MemberRepository memberRepository) {
        this.memberRepository = memberRepository;
    }

    @Transactional
    public void register(Member member) throws Exception {
        log.info("Registering " + member.getName());
        memberRepository.save(member);
    }
}
