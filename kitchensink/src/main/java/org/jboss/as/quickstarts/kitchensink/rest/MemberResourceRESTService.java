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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import org.jboss.as.quickstarts.kitchensink.data.MemberRepository;
import org.jboss.as.quickstarts.kitchensink.model.Member;
import org.jboss.as.quickstarts.kitchensink.service.MemberNotFoundException;
import org.jboss.as.quickstarts.kitchensink.service.MemberRegistration;

/**
 * Member REST endpoints — reads and creates rows in the {@code member} table.
 *
 * <p>MIGRATION (JBoss EAP 8 / Jakarta EE 10 -&gt; Spring Boot 3.x): converted from a JAX-RS
 * {@code @Path("/members") @RequestScoped} resource to a Spring MVC {@code @RestController} based at
 * {@code /api/members} (externally {@code /kitchensink/api/members}). Bean validation moves from a
 * manually-invoked {@code Validator} to {@code @Valid @RequestBody}, whose
 * {@code MethodArgumentNotValidException} is translated to the legacy 400 field-error map by
 * {@link RestExceptionHandler}. The duplicate-email case still returns 409 with
 * {@code {"email":"Email taken"}}, and successful creation still returns HTTP 200 with an empty body.
 * The {@code findById}/{@code findByEmail} repository calls now return {@link java.util.Optional}.</p>
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
     * GET /api/members/{id} — looks up a member by numeric ID. The {@code [0-9]+} constraint preserves
     * the legacy numeric-only path; a missing member yields 404 (via {@link MemberNotFoundException}
     * mapped in {@link RestExceptionHandler}).
     */
    @GetMapping("/{id:[0-9]+}")
    public Member lookupMemberById(@PathVariable("id") long id) {
        return repository.findById(id)
            .orElseThrow(() -> new MemberNotFoundException("Member not found: " + id));
    }

    /**
     * POST /api/members — creates a new member from the supplied JSON.
     *
     * <p>Validation is performed by {@code @Valid}; on failure Spring raises
     * {@code MethodArgumentNotValidException}, which {@link RestExceptionHandler} converts to a 400
     * response carrying a field-to-message map. A duplicate email returns 409 with
     * {@code {"email":"Email taken"}}. On success the member is registered and an empty 200 response
     * is returned (matching the legacy contract the PHP client expects).</p>
     *
     * @param member the member to create (validated)
     * @return 200 (empty) on success, or 409 with an {@code email} message if the email is taken
     */
    @PostMapping
    public ResponseEntity<?> createMember(@Valid @RequestBody Member member) {
        // Enforce the email uniqueness constraint (the @UniqueConstraint on Member.email). This is
        // the legacy "ValidationException -> 409 {email: Email taken}" behavior, kept inline rather
        // than as a global mapping because no duplicate-email exception type is defined.
        if (emailAlreadyExists(member.getEmail())) {
            Map<String, String> responseObj = new HashMap<>();
            responseObj.put("email", "Email taken");
            return ResponseEntity.status(HttpStatus.CONFLICT).body(responseObj);
        }

        registration.register(member);

        // Legacy contract: successful creation returns 200 with an empty body.
        return ResponseEntity.ok().build();
    }

    /**
     * Checks whether a member with the given email already exists. This is the application-level
     * guard for the {@code @UniqueConstraint(columnNames = "email")} on {@link Member}.
     *
     * @param email the email to check
     * @return {@code true} if a member with that email already exists
     */
    public boolean emailAlreadyExists(String email) {
        return repository.findByEmail(email).isPresent();
    }
}
