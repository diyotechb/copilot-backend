package com.example.livetranscription.config;

/**
 * Mirrors ROLE_GROUPS in the frontend (copilot/src/constants/roles.js).
 * Keep in sync — these are the authorization sets enforced on /api/** endpoints.
 */
public final class RoleGroups {

    public static final String[] STAFF = {
            "ADMIN", "SUPER_ADMIN", "DIYO_EMP"
    };

    public static final String[] ALL_AUTHORIZED = {
            "ADMIN", "SUPER_ADMIN", "DIYO_EMP", "COPILOT_USER", "DIYO_EXTERNAL"
    };

    public static final String[] TRANSCRIPTION_ACCESS = {
            "ADMIN", "SUPER_ADMIN", "DIYO_EMP", "DIYO_EXTERNAL"
    };

    private RoleGroups() {}
}
