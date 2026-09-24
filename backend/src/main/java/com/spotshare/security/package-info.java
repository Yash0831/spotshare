/**
 * Security plumbing. JWT access token (15 min) + rotating refresh tokens
 * (hashed in DB, single-use rotation); RBAC: USER, ADMIN. The JWT filter chain
 * and method-security configuration land here in Phase 4/6.
 */
package com.spotshare.security;
