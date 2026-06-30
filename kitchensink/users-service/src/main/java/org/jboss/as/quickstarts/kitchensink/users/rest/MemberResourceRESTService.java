package org.jboss.as.quickstarts.kitchensink.users.rest;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Valid;
import jakarta.validation.ValidationException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import org.jboss.as.quickstarts.kitchensink.users.dto.MemberTierResponse;
import org.jboss.as.quickstarts.kitchensink.users.exception.MemberNotFoundException;
import org.jboss.as.quickstarts.kitchensink.users.model.Member;
import org.jboss.as.quickstarts.kitchensink.users.repository.MemberRepository;
import org.jboss.as.quickstarts.kitchensink.users.service.MemberRegistration;

/**
 * REST web edge for users-service member management.
 *
 * <p>Spring MVC replacement for the legacy JAX-RS {@code MemberResourceRESTService}. Every original
 * member path and HTTP status semantic is preserved under the base mapping {@code /api/members}
 * (combined with the users-service context-path {@code /users}, the external base is
 * {@code /users/api/members}).</p>
 *
 * <p>Two cross-domain endpoints are added: the Contract-2 tier read ({@code GET /{id}/tier}) and the
 * GAP-3 spend increment ({@code POST /{id}/spend}). The controller stays thin: it delegates member
 * creation to {@link MemberRegistration}, reads through {@link MemberRepository}, and returns the tier
 * DTO directly. There are no native queries and no outbound cross-service HTTP calls here -- this is a
 * pure producer edge.</p>
 */
@RestController
@RequestMapping("/api/members")
public class MemberResourceRESTService {

    private final MemberRepository memberRepository;
    private final MemberRegistration registration;

    // Constructor injection (single constructor -> no @Autowired required).
    public MemberResourceRESTService(MemberRepository memberRepository, MemberRegistration registration) {
        this.memberRepository = memberRepository;
        this.registration = registration;
    }

    /** GET /api/members - list all members ordered by name. */
    @GetMapping
    public List<Member> listAllMembers() {
        return memberRepository.findAllByOrderByNameAsc();
    }

    /** GET /api/members/{id} - single member; unknown id -> 404. */
    @GetMapping("/{id}")
    public Member lookupMemberById(@PathVariable Long id) {
        return memberRepository.findById(id)
                .orElseThrow(() -> new MemberNotFoundException("Member not found: " + id));
    }

    /**
     * POST /api/members - create a member from JSON.
     *
     * <p>Returns 200 OK with an empty body on success (intentionally NOT 201, to preserve
     * {@code RemoteMemberRegistrationIT} parity). Bean Validation runs via {@code @Valid}; a
     * validation failure becomes 400 (see {@link #handleValidationErrors}) and a duplicate email
     * becomes 409 (see {@link #handleDuplicateEmail}).</p>
     */
    @PostMapping
    public ResponseEntity<Void> createMember(@Valid @RequestBody Member member) throws Exception {
        // Uniqueness check preserved from the legacy resource: a duplicate email is signalled with a
        // jakarta.validation.ValidationException, which the @ExceptionHandler below maps to 409.
        if (emailAlreadyExists(member.getEmail())) {
            throw new ValidationException("Unique Email Violation");
        }
        registration.register(member);
        // 200 OK with empty body (NOT 201) -- preserves the legacy Response.ok().build() contract.
        return ResponseEntity.ok().build();
    }

    /**
     * Contract 2 (Tier): GET /api/members/{id}/tier -> 200 with {@code {"tier":"GOLD"}}.
     * Unknown member id -> {@link MemberNotFoundException} (404).
     */
    @GetMapping("/{id}/tier")
    public MemberTierResponse getMemberTier(@PathVariable Long id) {
        Member member = memberRepository.findById(id)
                .orElseThrow(() -> new MemberNotFoundException("Member not found: " + id));
        return new MemberTierResponse(member.getTier());
    }

    /**
     * GAP-3 (cross-domain spend increment): POST /api/members/{id}/spend with body
     * {@code {"amount":<number>}}.
     *
     * <p>Cross-domain decision: orders-service invokes this endpoint AFTER its order transaction
     * commits (post-commit, eventually consistent) because users-service owns the {@code member}
     * table and orders-service must never write it directly. The increment runs inside this
     * controller's own local {@code @Transactional} boundary; the nightly tier recalculation
     * reconciles spend from confirmed-order history as a backstop. Unknown member id -> 404.</p>
     */
    @PostMapping("/{id}/spend")
    @Transactional
    public ResponseEntity<Void> incrementMemberSpend(@PathVariable Long id,
                                                     @RequestBody Map<String, BigDecimal> body) {
        Member member = memberRepository.findById(id)
                .orElseThrow(() -> new MemberNotFoundException("Member not found: " + id));
        BigDecimal amount = (body != null) ? body.get("amount") : null;
        if (amount == null) {
            amount = BigDecimal.ZERO;
        }
        BigDecimal current = (member.getTotalSpend() != null) ? member.getTotalSpend() : BigDecimal.ZERO;
        member.setTotalSpend(current.add(amount));
        memberRepository.save(member);
        return ResponseEntity.ok().build();
    }

    /**
     * Returns whether a member with the given email is already registered. Backed by the Spring Data
     * derived query {@code findByEmail(...).isPresent()} (replaces the legacy Criteria query plus
     * {@code NoResultException} handling).
     */
    public boolean emailAlreadyExists(String email) {
        return memberRepository.findByEmail(email).isPresent();
    }

    // --- Exception handlers: preserve the legacy 400 (validation) and 409 (duplicate email) semantics ---

    /** Bean Validation failures on the {@code @Valid @RequestBody} create -> 400 with a field -> message map. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidationErrors(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new HashMap<>();
        for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
            errors.put(fieldError.getField(), fieldError.getDefaultMessage());
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errors);
    }

    /**
     * Programmatic constraint violations -> 400 with a field -> message map. Mirrors the legacy
     * {@code createViolationResponse} helper (keyed by the violated property path).
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Map<String, String>> handleConstraintViolation(ConstraintViolationException ex) {
        Map<String, String> errors = new HashMap<>();
        for (ConstraintViolation<?> violation : ex.getConstraintViolations()) {
            errors.put(violation.getPropertyPath().toString(), violation.getMessage());
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errors);
    }

    /**
     * A non-constraint {@link ValidationException} (the duplicate-email signal) -> 409 with body
     * {@code {"email":"Email taken"}}. Because {@link ConstraintViolationException} is more specific,
     * Spring routes constraint violations to {@link #handleConstraintViolation} and only the plain
     * duplicate-email {@code ValidationException} reaches this handler.
     */
    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<Map<String, String>> handleDuplicateEmail(ValidationException ex) {
        Map<String, String> responseObj = new HashMap<>();
        responseObj.put("email", "Email taken");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(responseObj);
    }
}
