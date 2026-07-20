package com.example.ethics.repository;
import com.example.ethics.model.EthicsReport; import java.util.*; import org.springframework.data.jpa.repository.JpaRepository;
public interface EthicsReportRepository extends JpaRepository<EthicsReport,UUID>{ Optional<EthicsReport> findByCaseId(UUID caseId); }
