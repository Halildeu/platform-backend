// DD-5 negative fixture: unknown relation NOT in canonical OR alias —
// should FAIL check (would cause runtime OpenFGA HTTP 400 in production).
package com.example.fixtures;

public class BadController {

    // "owner" is not in RELATION_ALIASES and not a canonical module relation
    @RequireModule(value = "ACCESS", relation = "owner")
    public void promoteUser() { }

    // "super-admin" likewise unknown
    @RequireModule(value = "USER_MANAGEMENT", relation = "super-admin")
    public void resetAllPasswords() { }
}
