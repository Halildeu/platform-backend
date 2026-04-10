package com.example.commonauth.scope;

@FunctionalInterface
public interface AuthzVersionProvider {
    long getCurrentVersion();
}
