package com.example.user.service;

import com.example.user.model.User;
import com.example.user.repository.UserRepository;
import com.example.user.repository.UserSpecifications;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Faz 24 (gitops#3834) — who a signed-in user may pick as a task assignee.
 *
 * <p>The admin user grid ({@code GET /api/v1/users}) requires {@code USER_READ} and returns full
 * profile rows; the picker needs neither. This answers only "assignable people visible to this
 * requester that match the text", using the directory's own visibility rule (see
 * {@link UserSpecifications#visibleToCompany}).
 */
@Service
public class AssigneeCandidateService {

    public static final int MIN_QUERY_LENGTH = 2;
    public static final int DEFAULT_LIMIT = 10;

    private static final Sort BY_NAME = Sort.by(Sort.Order.asc("name").ignoreCase(), Sort.Order.asc("id"));

    private final UserRepository userRepository;

    public AssigneeCandidateService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * @return the matching candidates, or empty when the requester is not an active member of the
     *     directory — the caller must deny, not fall back to an unscoped search
     */
    @Transactional(readOnly = true)
    public Optional<List<User>> search(String requesterSubject, String query, int limit) {
        Optional<User> requester = userRepository.findByKcSubject(requesterSubject.trim())
                .filter(user -> user.isEnabled() && user.getDeletedAt() == null);
        if (requester.isEmpty()) {
            return Optional.empty();
        }
        Specification<User> spec = UserSpecifications.assignable()
                .and(UserSpecifications.nameOrEmailContains(query))
                .and(UserSpecifications.visibleToCompany(requester.get().getCompanyId()));
        return Optional.of(userRepository.findAll(spec, PageRequest.of(0, limit, BY_NAME)).getContent());
    }
}
