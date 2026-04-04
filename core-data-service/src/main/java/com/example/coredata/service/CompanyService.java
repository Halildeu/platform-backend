package com.example.coredata.service;

import com.example.coredata.dto.CompanyCreateRequest;
import com.example.coredata.dto.CompanyDto;
import com.example.coredata.dto.CompanySearchRequest;
import com.example.coredata.dto.CompanyUpdateRequest;
import com.example.coredata.model.Company;
import com.example.coredata.repository.CompanyRepository;
import com.example.commonauth.openfga.OpenFgaAuthzService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.util.Optional;

@Service
public class CompanyService {

    private static final Logger log = LoggerFactory.getLogger(CompanyService.class);

    private final CompanyRepository companyRepository;
    private final OpenFgaAuthzService authzService;

    public CompanyService(CompanyRepository companyRepository, OpenFgaAuthzService authzService) {
        this.companyRepository = companyRepository;
        this.authzService = authzService;
    }

    @Transactional(readOnly = true)
    public Page<CompanyDto> search(CompanySearchRequest request, Pageable pageable) {
        Specification<Company> spec = buildSpec(request);
        return companyRepository.findAll(spec, pageable).map(this::toDto);
    }

    @Transactional(readOnly = true)
    public CompanyDto getById(Long id) {
        return companyRepository.findById(id)
                .map(this::toDto)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Company not found"));
    }

    @Transactional
    public CompanyDto create(CompanyCreateRequest req, String actor) {
        if (companyRepository.existsByCompanyCode(req.companyCode())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "company_code already exists");
        }
        Company c = new Company();
        c.setCompanyCode(req.companyCode());
        c.setCompanyName(req.companyName());
        c.setShortName(req.shortName());
        c.setCompanyType(req.companyType());
        c.setTaxNumber(req.taxNumber());
        c.setTaxOffice(req.taxOffice());
        c.setCountryCode(req.countryCode());
        c.setCity(req.city());
        c.setStatus(Optional.ofNullable(req.status()).orElse("ACTIVE"));
        c.setCreatedBy(actor);
        Company saved = companyRepository.save(c);

        writeCompanyTuples(saved.getId(), actor);

        return toDto(saved);
    }

    private void writeCompanyTuples(Long companyId, String actorUserId) {
        String companyObj = String.valueOf(companyId);
        try {
            authzService.writeTuple(actorUserId, "admin", "company", companyObj);
            log.info("OpenFGA: granted admin on company:{} to user:{}", companyId, actorUserId);
        } catch (Exception e) {
            log.error("OpenFGA: failed to grant admin on company:{} to user:{}", companyId, actorUserId, e);
        }
    }

    @Transactional
    public CompanyDto update(Long id, CompanyUpdateRequest req, String actor) {
        Company c = companyRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Company not found"));

        if (req.companyName() != null) c.setCompanyName(req.companyName());
        if (req.shortName() != null) c.setShortName(req.shortName());
        if (req.companyType() != null) c.setCompanyType(req.companyType());
        if (req.taxNumber() != null) c.setTaxNumber(req.taxNumber());
        if (req.taxOffice() != null) c.setTaxOffice(req.taxOffice());
        if (req.countryCode() != null) c.setCountryCode(req.countryCode());
        if (req.city() != null) c.setCity(req.city());
        if (req.status() != null) c.setStatus(req.status());
        c.setUpdatedBy(actor);

        Company saved = companyRepository.save(c);
        return toDto(saved);
    }

    private Specification<Company> buildSpec(CompanySearchRequest req) {
        Specification<Company> spec = Specification.where(null);
        if (req == null) return spec;
        if (req.companyCode() != null && !req.companyCode().isBlank()) {
            spec = spec.and((root, query, cb) -> cb.equal(cb.lower(root.get("companyCode")), req.companyCode().toLowerCase()));
        }
        if (req.companyName() != null && !req.companyName().isBlank()) {
            String like = "%" + req.companyName().trim().toLowerCase() + "%";
            spec = spec.and((root, query, cb) -> cb.like(cb.lower(root.get("companyName")), like));
        }
        if (req.status() != null && !req.status().isBlank()) {
            spec = spec.and((root, query, cb) -> cb.equal(cb.lower(root.get("status")), req.status().toLowerCase()));
        }
        return spec;
    }

    private CompanyDto toDto(Company c) {
        return new CompanyDto(
                c.getId(),
                c.getCompanyCode(),
                c.getCompanyName(),
                c.getShortName(),
                c.getCompanyType(),
                c.getTaxNumber(),
                c.getTaxOffice(),
                c.getCountryCode(),
                c.getCity(),
                c.getStatus(),
                c.getCreatedAt(),
                c.getUpdatedAt()
        );
    }
}
