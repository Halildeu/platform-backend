package com.example.user.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.example.user.model.User;

import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long>, JpaSpecificationExecutor<User> {

    /**
     * Check if a user exists by email
     * 
     * @param email The email to check
     * @return true if the email exists, false otherwise
     */
    boolean existsByEmail(String email);

    /**
     * Find a user by email
     * 
     * @param email The email to search for
     * @return Optional<User> if the user exists
     */
    Optional<User> findByEmail(String email);

    @Modifying(clearAutomatically = true)
    @Query("update User u set u.sessionTimeoutMinutes = :timeout where u.sessionTimeoutMinutes is null or u.sessionTimeoutMinutes < 1")
    int normalizeSessionTimeouts(@Param("timeout") int timeout);
}
