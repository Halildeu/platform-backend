package com.example.coredata.controller;

import com.example.coredata.dto.CompanyCreateRequest;
import com.example.coredata.model.Company;
import com.example.coredata.repository.CompanyRepository;
import com.example.commonauth.PermissionCodes;
import com.example.commonauth.openfga.OpenFgaAuthzService;
import com.example.commonauth.openfga.OpenFgaProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = {
        com.example.coredata.CoreDataServiceApplication.class,
        CompanyControllerSecurityTest.TestAuthzConfig.class
})
@AutoConfigureMockMvc
@Transactional
class CompanyControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CompanyRepository companyRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setup() {
        companyRepository.deleteAll();
        Company c = new Company();
        c.setCompanyCode("ACME");
        c.setCompanyName("Acme Corp");
        c.setStatus("ACTIVE");
        companyRepository.save(c);
    }

    @Test
    void unauthorizedWithoutToken() throws Exception {
        mockMvc.perform(get("/api/v1/companies"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void forbiddenWhenMissingReadPermission() throws Exception {
        mockMvc.perform(get("/api/v1/companies")
                        .with(SecurityMockMvcRequestPostProcessors.jwt()
                                .authorities(new SimpleGrantedAuthority("dummy"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void listWithReadPermissionIsOk() throws Exception {
        mockMvc.perform(get("/api/v1/companies")
                        .with(SecurityMockMvcRequestPostProcessors.jwt()
                                .authorities(new SimpleGrantedAuthority(PermissionCodes.COMPANY_READ))))
                .andExpect(status().isOk());
    }

    @Test
    void createRequiresWritePermission() throws Exception {
        CompanyCreateRequest req = new CompanyCreateRequest(
                "NEWCO", "New Company", null, null, null, null, null, null, "ACTIVE"
        );

        mockMvc.perform(post("/api/v1/companies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req))
                        .with(SecurityMockMvcRequestPostProcessors.jwt()
                                .authorities(new SimpleGrantedAuthority(PermissionCodes.COMPANY_READ))))
                .andExpect(status().isForbidden());
    }

    @Test
    void createWithWritePermissionSucceeds() throws Exception {
        CompanyCreateRequest req = new CompanyCreateRequest(
                "NEWCO", "New Company", null, null, null, null, null, null, "ACTIVE"
        );

        mockMvc.perform(post("/api/v1/companies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req))
                        .with(SecurityMockMvcRequestPostProcessors.jwt()
                                .authorities(new SimpleGrantedAuthority(PermissionCodes.COMPANY_WRITE))))
                .andExpect(status().isCreated());
    }

    @Test
    void duplicateCompanyCodeReturnsConflict() throws Exception {
        CompanyCreateRequest req = new CompanyCreateRequest(
                "ACME", "Duplicate", null, null, null, null, null, null, "ACTIVE"
        );

        mockMvc.perform(post("/api/v1/companies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req))
                        .with(SecurityMockMvcRequestPostProcessors.jwt()
                                .authorities(new SimpleGrantedAuthority(PermissionCodes.COMPANY_WRITE))))
                .andExpect(status().isConflict());
    }

    @TestConfiguration
    static class TestAuthzConfig {
        @Bean
        @Primary
        public OpenFgaAuthzService testOpenFgaAuthzService(OpenFgaProperties openFgaProperties) {
            return new AuthorityCheckingAuthzService(openFgaProperties);
        }
    }

    /**
     * Test-only OpenFgaAuthzService that delegates permission checks to Spring Security
     * granted authorities instead of making real OpenFGA calls.
     * Maps OpenFGA relations to legacy permission codes used by the test fixtures.
     */
    static class AuthorityCheckingAuthzService extends OpenFgaAuthzService {
        AuthorityCheckingAuthzService(OpenFgaProperties properties) {
            super(null, properties);
        }

        @Override
        public boolean check(String userId, String relation, String objectType, String objectId) {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth == null || !auth.isAuthenticated()) {
                return false;
            }
            String required = resolveRequiredAuthority(relation, objectType, objectId);
            if (required == null) {
                return false;
            }
            return auth.getAuthorities().stream()
                    .anyMatch(a -> a.getAuthority().equals(required));
        }

        private String resolveRequiredAuthority(String relation, String objectType, String objectId) {
            if (!"module".equals(objectType)) {
                return null;
            }
            return switch (relation) {
                case PermissionCodes.RELATION_CAN_VIEW -> resolveReadAuthority(objectId);
                case PermissionCodes.RELATION_CAN_MANAGE -> resolveWriteAuthority(objectId);
                default -> null;
            };
        }

        private String resolveReadAuthority(String module) {
            return switch (module) {
                case PermissionCodes.MODULE_COMPANY -> PermissionCodes.COMPANY_READ;
                default -> null;
            };
        }

        private String resolveWriteAuthority(String module) {
            return switch (module) {
                case PermissionCodes.MODULE_COMPANY -> PermissionCodes.COMPANY_WRITE;
                default -> null;
            };
        }
    }
}
