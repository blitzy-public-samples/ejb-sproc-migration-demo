package org.jboss.as.quickstarts.kitchensink.users.rest;

import jakarta.validation.Valid;

import org.jboss.as.quickstarts.kitchensink.users.dto.MemberSpendIncrementRequest;
import org.jboss.as.quickstarts.kitchensink.users.service.MemberSpendService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * INTERNAL (service-to-service) REST controller for the members API (users-service).
 *
 * <p>Exposes the lifetime-spend write contract that orders-service calls when it confirms an order:
 * {@code POST /internal/members/{id}/total-spend} with body {@link MemberSpendIncrementRequest}. This
 * realizes the Source-A {@code process_order} side-effect of incrementing {@code total_spend} by the
 * order SUBTOTAL (AAP &sect;0.6.1 / &sect;0.7.3) while honoring the cross-domain boundary rule
 * (AAP &sect;0.7.2): orders-service never touches the {@code member} table directly.</p>
 *
 * <p><b>Security (review M13):</b> everything under {@code /internal/**} is gated by
 * {@code InternalApiKeyFilter}, which fail-closed requires a matching {@code X-Internal-Api-Key}
 * header (shared {@code INTERNAL_API_KEY} secret). This controller therefore assumes the caller has
 * already been authenticated by the filter and contains no further auth logic.</p>
 *
 * <p>Status mapping: 200 on success; 400 (Bean Validation, {@code @Valid}) when the amount is missing
 * or non-positive; 404 when the member does not exist (raised by {@link MemberSpendService}).</p>
 */
@RestController
@RequestMapping("/internal")
public class InternalMemberResourceRESTService {

    private final MemberSpendService memberSpendService;

    public InternalMemberResourceRESTService(MemberSpendService memberSpendService) {
        this.memberSpendService = memberSpendService;
    }

    /**
     * Increments the member's lifetime {@code total_spend} by the supplied amount (order subtotal).
     *
     * @param id      the member id (path)
     * @param request the validated increment request ({@code {"amount": 49.99}})
     * @return 200 OK with an empty body on success
     */
    @PostMapping(value = "/members/{id}/total-spend", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> incrementTotalSpend(
            @PathVariable("id") Long id,
            @Valid @RequestBody MemberSpendIncrementRequest request) {
        memberSpendService.incrementTotalSpend(id, request.amount());
        return ResponseEntity.ok().build();
    }
}
