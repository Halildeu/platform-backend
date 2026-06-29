package com.example.endpointadmin.service.rolloutfailure;

import com.example.endpointadmin.dto.v1.admin.RolloutFailureEscalationResponse;

public interface RolloutFailureIssuePublisher {

    PublishedIssue createIssue(RolloutFailureEscalationResponse issue);

    record PublishedIssue(String htmlUrl, long number) {
    }
}
