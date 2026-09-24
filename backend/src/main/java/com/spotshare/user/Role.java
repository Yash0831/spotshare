package com.spotshare.user;

/**
 * Application roles. Host/driver are personas, not roles — any USER can both
 * list spaces and reserve them. ADMIN exists for future moderation tooling.
 */
public enum Role {
    USER,
    ADMIN
}
