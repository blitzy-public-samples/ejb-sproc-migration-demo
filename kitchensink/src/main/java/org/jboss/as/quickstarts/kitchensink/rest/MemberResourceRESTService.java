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
package org.jboss.as.quickstarts.kitchensink.rest;

import java.math.BigDecimal;
import java.util.List;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import org.jboss.as.quickstarts.kitchensink.data.MemberRepository;
import org.jboss.as.quickstarts.kitchensink.model.Member;
import org.jboss.as.quickstarts.kitchensink.service.MemberRegistration;

/**
 * Spring MVC {@code @RestController} producing the members REST service: it reads and creates rows in
 * the {@code member} table under {@code /api/members} (externally {@code /kitchensink/api/members}).
 *
 * <p>Migrated from the Jakarta EE 10 JAX-RS resource to Spring Boot 3.x. Bean validation is now driven
 * by {@code @Valid @RequestBody}, and all inline HTTP error-construction has moved to
 * {@link RestExceptionHandler}: validation failures map to 400 with a {@code {field : message}} map,
 * and the duplicate-email case (signalled via {@link RestExceptionHandler.DuplicateEmailException})
 * maps to 409 with body {@code {"email":"Email taken"}}. Successful creation returns HTTP 200 with an
 * empty body and a missing lookup returns an empty 404, preserving the original contract. The
 * repository {@code findById}/{@code findByEmail} calls now return {@link java.util.Optional}.</p>
 */
@RestController
@RequestMapping("/api/members")
public class MemberResourceRESTService {

    private final MemberRepository repository;
    private final MemberRegistration registration;

    public MemberResourceRESTService(MemberRepository repository, MemberRegistration registration) {
        this.repository = repository;
        this.registration = registration;
    }

    /**
     * GET /api/members — lists all members ordered by name.
     */
    @GetMapping
    public List<Member> listAllMembers() {
        return repository.findAllByOrderByNameAsc();
    }

    /**
     * GET /api/members/{id} — looks up a member by id. A present member yields HTTP 200 with the
     * member serialized as the JSON body; a missing member yields an empty HTTP 404, preserving the
     * original empty not-found response. Binding {@code id} as a {@link Long} means a non-numeric path
     * segment produces a framework 400 type-mismatch, preserving the legacy numeric-only intent
     * without an explicit path regex.
     *
     * @param id the numeric member id from the path
     * @return 200 with the member when found, otherwise an empty 404
     */
    @GetMapping("/{id}")
    public ResponseEntity<Member> lookupMemberById(@PathVariable("id") Long id) {
        return repository.findById(id)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * POST /api/members — creates a new member from the supplied JSON.
     *
     * <p>Validation is performed by {@code @Valid} before the method body runs; on failure Spring
     * raises a {@code MethodArgumentNotValidException}, which {@link RestExceptionHandler} converts to
     * a 400 response carrying a field-to-message map. Because {@code @Valid} runs first, validation
     * 400s take precedence over the 409 duplicate-email check below — preserving the original
     * ordering (validate, then check email uniqueness).</p>
     *
     * <p>The email uniqueness constraint (the {@code @UniqueConstraint(columnNames = "email")} on
     * {@link Member}) is enforced inline: if a member with the same email already exists,
     * {@link RestExceptionHandler.DuplicateEmailException} is thrown and the advice maps it to 409
     * with the exact body {@code {"email":"Email taken"}}. On success the member is registered and an
     * empty HTTP 200 response is returned — the legacy contract the PHP storefront and
     * {@code RemoteMemberRegistrationIT} depend on.</p>
     *
     * @param member the member to create (validated by {@code @Valid})
     * @return an empty HTTP 200 on success
     */
    @PostMapping
    public ResponseEntity<Void> createMember(@Valid @RequestBody Member member) {
        if (repository.findByEmail(member.getEmail()).isPresent()) {
            throw new RestExceptionHandler.DuplicateEmailException();
        }

        // SECURITY — Mass assignment / over-posting (CWE-915): @RequestBody binds the FULL Member entity,
        // which also exposes server-controlled fields (id, tier, total_spend, tier_updated_at). Registration
        // accepts only name/email/phoneNumber from the client; force every server-owned field to its default
        // here so a client cannot over-post them (e.g. tier="PLATINUM" or a non-zero total_spend would
        // otherwise skew discount/tier behavior). id is nulled so save() always INSERTs a fresh row instead
        // of risking a merge onto an existing member.
        member.setId(null);
        member.setTier("BRONZE");
        member.setTotalSpend(BigDecimal.ZERO);
        member.setTierUpdatedAt(null);

        registration.register(member);

        // Legacy contract: successful creation returns 200 with an empty body.
        return ResponseEntity.ok().build();
    }
}
