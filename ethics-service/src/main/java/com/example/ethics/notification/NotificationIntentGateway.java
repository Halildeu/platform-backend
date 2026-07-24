package com.example.ethics.notification;

import com.example.ethics.model.NotificationOutbox;

public interface NotificationIntentGateway {
    void submit(NotificationOutbox row);
}
