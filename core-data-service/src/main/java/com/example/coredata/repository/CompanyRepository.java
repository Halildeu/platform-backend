package com.example.coredata.repository;

import com.example.coredata.model.Company;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface CompanyRepository extends JpaRepository<Company, Long>, JpaSpecificationExecutor<Company> {
    boolean existsByCompanyCode(String companyCode);
}
