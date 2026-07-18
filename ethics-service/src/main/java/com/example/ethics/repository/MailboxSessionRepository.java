package com.example.ethics.repository;
import com.example.ethics.model.MailboxSession; import org.springframework.data.jpa.repository.JpaRepository;
public interface MailboxSessionRepository extends JpaRepository<MailboxSession,String>{}
