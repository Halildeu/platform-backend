package com.example.ethics.repository;
import com.example.ethics.model.EthicsCase; import java.util.*; import org.springframework.data.jpa.repository.JpaRepository;
public interface EthicsCaseRepository extends JpaRepository<EthicsCase,UUID>{ List<EthicsCase> findAllByOrgIdOrderByUpdatedAtDesc(UUID orgId); Optional<EthicsCase> findByIdAndOrgId(UUID id,UUID orgId); }
