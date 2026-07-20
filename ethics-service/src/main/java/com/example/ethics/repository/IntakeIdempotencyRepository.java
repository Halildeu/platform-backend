package com.example.ethics.repository;
import com.example.ethics.model.IntakeIdempotency; import java.util.*; import org.springframework.data.jpa.repository.JpaRepository;
public interface IntakeIdempotencyRepository extends JpaRepository<IntakeIdempotency,UUID>{ Optional<IntakeIdempotency> findByOrgIdAndChannelAndIdempotencyKey(UUID orgId,String channel,String key); }
