package com.spotshare.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A SpotShare account. Host/driver are personas, not roles — any user can both
 * list spaces and reserve them. Maps to the {@code users} table owned by Flyway
 * migration V1 (Hibernate validates, never creates).
 */
@Entity
@Table(name = "users")
public class User {

    @Id
    // Assigned in Java (not by the database) so the id is stable from
    // construction — usable in tokens and tests before the row is persisted.
    // The DB default gen_random_uuid() is only a backstop.
    private UUID id = UUID.randomUUID();

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "first_name", nullable = false)
    private String firstName;

    @Column(name = "last_name", nullable = false)
    private String lastName;

    @Column(name = "phone")
    private String phone;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Role role = Role.USER;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected User() {
        // JPA
    }

    public User(String email, String passwordHash, String firstName, String lastName,
                String phone, Role role) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.firstName = firstName;
        this.lastName = lastName;
        this.phone = phone;
        this.role = role;
        this.createdAt = OffsetDateTime.now();
        this.updatedAt = OffsetDateTime.now();
    }

    public UUID getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getFirstName() {
        return firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public String getPhone() {
        return phone;
    }

    public Role getRole() {
        return role;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}
