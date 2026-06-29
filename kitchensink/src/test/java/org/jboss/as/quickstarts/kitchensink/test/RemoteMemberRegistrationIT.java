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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

/**
 * Remote (live HTTP) integration test for the member-creation endpoint.
 *
 * <p>MIGRATION (JBoss EAP 8 / Jakarta EE 10 -&gt; Spring Boot 3.x): rewritten from a plain JDK-HttpClient
 * JUnit 4 test that targeted a deployed EAP server at {@code /rest/members} to a
 * {@code @SpringBootTest(webEnvironment = RANDOM_PORT)} + {@link TestRestTemplate} test that exercises the
 * embedded server. The endpoint moves to {@code /api/members}. The request URL includes the configured
 * {@code server.servlet.context-path} (which is {@code /kitchensink} — inherited from the base
 * {@code application.properties}, since the test profile does not override it), so the effective path is
 * {@code /kitchensink/api/members}.</p>
 *
 * <p>This test is intentionally NOT {@code @Transactional}: it performs a real HTTP round trip, so the
 * member is committed by the server. {@code jane@mailinator.com} is therefore committed here;
 * {@link MemberRegistrationIT} (which uses the same email) rolls back and runs earlier alphabetically, so
 * there is no duplicate-email collision in the shared Testcontainers database.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class RemoteMemberRegistrationIT {

    @LocalServerPort
    private int port;

    @Value("${server.servlet.context-path:}")
    private String contextPath;

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void testRegister() {
        Map<String, String> json = new LinkedHashMap<>();
        json.put("name", "Jane Doe");
        json.put("email", "jane@mailinator.com");
        json.put("phoneNumber", "2125551234");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, String>> request = new HttpEntity<>(json, headers);

        String url = "http://localhost:" + port + contextPath + "/api/members";
        ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);

        assertEquals(200, response.getStatusCode().value(), "Member creation should return HTTP 200");
        assertTrue(response.getBody() == null || response.getBody().isEmpty(),
            "Successful member creation should return an empty body");
    }
}
