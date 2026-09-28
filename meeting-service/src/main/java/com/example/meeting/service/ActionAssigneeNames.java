package com.example.meeting.service;

import com.example.meeting.dto.v1.admin.MeetingActionResponse;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Fills {@link MeetingActionResponse#assigneeDisplayName()} (gitops#3834).
 *
 * <p>Without it the task list shows the assignee as a Keycloak UUID — "assigned" but to nobody a
 * person can recognise. Called by the controller AFTER the service's transaction has ended, so no
 * database connection is held while the directory answers; one directory call per response.
 *
 * <p>Names are for reading only: when the directory cannot be asked, rows go out without names
 * (the UI shows a neutral "assigned" label) and a warning is logged without any subject or name.
 * The assignment itself is never affected.
 */
@Component
public class ActionAssigneeNames {

    private static final Logger log = LoggerFactory.getLogger(ActionAssigneeNames.class);

    /** user-service rejects longer subjects; such a value is not a directory subject anyway. */
    private static final int MAX_SUBJECT_LENGTH = 64;

    private final AssigneeDirectoryClient directory;

    public ActionAssigneeNames(AssigneeDirectoryClient directory) {
        this.directory = directory;
    }

    public List<MeetingActionResponse> withNames(List<MeetingActionResponse> actions) {
        Set<String> subjects = new LinkedHashSet<>();
        for (MeetingActionResponse action : actions) {
            String subject = action.assigneeSubject();
            if (subject != null && !subject.isBlank() && subject.length() <= MAX_SUBJECT_LENGTH) {
                subjects.add(subject);
            }
        }
        if (subjects.isEmpty()) {
            return actions;
        }
        Map<String, String> names;
        try {
            names = directory.resolveDisplayNames(subjects);
        } catch (AssigneeDirectoryClient.ResolutionUnavailableException ex) {
            log.warn("assignee display names unavailable ({}); {} action rows returned without names",
                    ex.getMessage(), actions.size());
            return actions;
        }
        return actions.stream()
                .map(action -> action.assigneeSubject() == null
                        ? action
                        : action.withAssigneeDisplayName(names.get(action.assigneeSubject())))
                .toList();
    }

    public MeetingActionResponse withName(MeetingActionResponse action) {
        return withNames(List.of(action)).get(0);
    }
}
