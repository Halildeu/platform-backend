package com.example.coredata.controller;

import com.example.coredata.dto.CompanyCreateRequest;
import com.example.coredata.dto.CompanyDto;
import com.example.coredata.dto.CompanySearchRequest;
import com.example.coredata.dto.CompanyUpdateRequest;
import com.example.coredata.dto.PagedResultDto;
import com.example.coredata.service.CompanyService;
import com.example.commonauth.PermissionCodes;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/companies")
public class CompanyController {

    private final CompanyService companyService;

    public CompanyController(CompanyService companyService) {
        this.companyService = companyService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('" + PermissionCodes.COMPANY_READ + "')")
    public ResponseEntity<PagedResultDto<CompanyDto>> list(@RequestParam(value = "companyCode", required = false) String companyCode,
                                                           @RequestParam(value = "companyName", required = false) String companyName,
                                                           @RequestParam(value = "status", required = false) String status,
                                                           @RequestParam(value = "page", defaultValue = "0") int page,
                                                           @RequestParam(value = "size", defaultValue = "20") int size,
                                                           @RequestParam(value = "sort", defaultValue = "id,asc") String sort) {
        Pageable pageable = PageRequest.of(page, size, parseSort(sort));
        CompanySearchRequest search = new CompanySearchRequest(companyCode, companyName, status);
        Page<CompanyDto> result = companyService.search(search, pageable);
        return ResponseEntity.ok(new PagedResultDto<>(result.getContent(), result.getTotalElements(), page, size));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('" + PermissionCodes.COMPANY_READ + "')")
    public CompanyDto getById(@PathVariable("id") Long id) {
        return companyService.getById(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('" + PermissionCodes.COMPANY_WRITE + "')")
    public CompanyDto create(@Valid @RequestBody CompanyCreateRequest request, Authentication authentication) {
        String actor = authentication != null ? authentication.getName() : null;
        return companyService.create(request, actor);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('" + PermissionCodes.COMPANY_WRITE + "')")
    public CompanyDto update(@PathVariable("id") Long id,
                             @Valid @RequestBody CompanyUpdateRequest request,
                             Authentication authentication) {
        String actor = authentication != null ? authentication.getName() : null;
        return companyService.update(id, request, actor);
    }

    private Sort parseSort(String sort) {
        // Beklenen format: field,direction (örn. companyName,asc)
        if (sort == null || sort.isBlank()) {
            return Sort.by("id").ascending();
        }
        String[] parts = sort.split(",");
        if (parts.length == 2) {
            String field = parts[0];
            String dir = parts[1].toLowerCase();
            return "desc".equals(dir) ? Sort.by(field).descending() : Sort.by(field).ascending();
        }
        return Sort.by(sort).ascending();
    }
}
