package com.example.ethics.repository;
import com.example.ethics.model.EthicsMessage; import java.util.*; import org.springframework.data.jpa.repository.JpaRepository;
public interface EthicsMessageRepository extends JpaRepository<EthicsMessage,UUID>{ List<EthicsMessage> findAllByCaseIdAndVisibilityInOrderByCreatedAtAsc(UUID caseId,Collection<String> visibility); Optional<EthicsMessage> findByCaseIdAndAuthorTypeAndIdempotencyKey(UUID caseId,String authorType,String key); }
