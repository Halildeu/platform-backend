package com.example.kcsmsotp;

/** Raised when the mint or intent leg of the send chain fails. */
public class SmsSendException extends Exception {

    public SmsSendException(String message) {
        super(message);
    }

    public SmsSendException(String message, Throwable cause) {
        super(message, cause);
    }
}
