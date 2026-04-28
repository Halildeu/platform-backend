// DD-5 positive fixture (alias): legacy aliases that map to canonical relations
// — should PASS check (RELATION_ALIASES bridge).
package com.example.fixtures;

public class AliasController {

    @RequireModule(value = "ACCESS", relation = "viewer")
    public void listRoles() { }  // viewer → can_view

    @RequireModule(value = "ACCESS", relation = "manager")
    public void createRole() { }  // manager → can_manage

    @RequireModule(value = "USER_MANAGEMENT", relation = "admin")
    public void deleteUser() { }  // admin → can_manage

    @RequireModule(value = "AUDIT", relation = "editor")
    public void editAuditConfig() { }  // editor → can_edit
}
