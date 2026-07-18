package com.example.ethics.repository;
import com.example.ethics.model.ReporterAccessGrant; import java.util.*; import org.springframework.data.jpa.repository.JpaRepository;
public interface ReporterAccessGrantRepository extends JpaRepository<ReporterAccessGrant,UUID>{}
