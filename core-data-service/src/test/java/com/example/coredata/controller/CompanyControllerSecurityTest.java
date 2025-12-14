package com.example.coredata.controller;

import com.example.coredata.dto.CompanyCreateRequest;
import com.example.coredata.model.Company;
import com.example.coredata.repository.CompanyRepository;
import com.example.commonauth.PermissionCodes;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
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
}
