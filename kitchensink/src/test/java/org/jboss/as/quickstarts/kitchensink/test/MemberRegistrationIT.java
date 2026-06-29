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
package org.jboss.as.quickstarts.kitchensink.test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import org.jboss.as.quickstarts.kitchensink.model.Member;
import org.jboss.as.quickstarts.kitchensink.service.MemberRegistration;

/**
 * Integration test for {@link MemberRegistration}.
 *
 * <p>MIGRATION (JBoss EAP 8 / Jakarta EE 10 -&gt; Spring Boot 3.x): rewritten from Arquillian/JUnit 4 to
 * {@code @SpringBootTest} + JUnit 5, with the collaborator {@code @Autowired}. The test is
 * {@code @Transactional} (rolled back), so {@code jane@mailinator.com} is NOT committed here — that email
 * is committed by {@link RemoteMemberRegistrationIT} (which performs a real HTTP POST and therefore cannot
 * roll back). Because Failsafe runs the integration tests in alphabetical order, this test (M) runs before
 * the remote test (R), so the rollback prevents a duplicate-email collision in the shared test database.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MemberRegistrationIT {

    @Autowired
    private MemberRegistration memberRegistration;

    @Test
    void testRegister() {
        Member newMember = new Member();
        newMember.setName("Jane Doe");
        newMember.setEmail("jane@mailinator.com");
        newMember.setPhoneNumber("2125551234");

        memberRegistration.register(newMember);

        assertNotNull(newMember.getId(), "Member should be persisted with a generated id");
    }
}
