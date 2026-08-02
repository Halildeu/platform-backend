package com.example.ethics.repository;

import com.example.ethics.model.ReporterIdentity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * ES-212. Deliberately narrow: find one by case, save, delete on retention expiry.
 * No {@code findAll}, no search by anything derived from the identity — there is
 * nothing searchable stored, and adding a way to list identities would create the
 * "who has reported" query this product must not be able to answer.
 */
public interface ReporterIdentityRepository extends JpaRepository<ReporterIdentity, UUID> {
}
