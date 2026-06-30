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
package org.jboss.as.quickstarts.kitchensink.users.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import org.jboss.as.quickstarts.kitchensink.users.model.Member;

/**
 * Spring Data JPA repository for the {@link Member} entity (users-service / member bounded context).
 *
 * <p>Replaces the monolith's hand-rolled {@code @ApplicationScoped} CDI repository that injected an
 * {@code EntityManager} and used the JPA Criteria API. Spring Data auto-implements this interface at
 * runtime; no {@code @Repository} annotation is required because the interface is discovered by the
 * default component scan rooted at {@code org.jboss.as.quickstarts.kitchensink.users}
 * (see {@code UsersServiceApplication}).</p>
 *
 * <p>{@code findById(Long)} and {@code save(...)} are inherited from {@link JpaRepository}.</p>
 */
public interface MemberRepository extends JpaRepository<Member, Long> {

    /**
     * Looks up a member by unique email address. The {@code email} column carries a UNIQUE
     * constraint (see {@code db/01_schema.sql}), so at most one row can match; returning
     * {@link Optional} lets callers detect "not found" without catching {@code NoResultException}.
     * Replaces the monolith's Criteria {@code getSingleResult()} lookup.
     */
    Optional<Member> findByEmail(String email);

    /**
     * Returns all members ordered ascending by {@code name}. Replaces the monolith's
     * {@code findAllOrderedByName()} Criteria query (ascending on {@code name}).
     */
    List<Member> findAllByOrderByNameAsc();
}
