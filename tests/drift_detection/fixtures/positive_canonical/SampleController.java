// DD-5 positive fixture: canonical relation names — should PASS check.
package com.example.fixtures;

@RequireModule(value = "ACCESS", relation = "can_view")
public class SampleController {

    @RequireModule(value = "ACCESS", relation = "can_manage")
    public void createRole() { }

    @RequireModule(value = "AUDIT", relation = "can_view")
    public void listAuditEvents() { }

    @RequireModule(value = "USER_MANAGEMENT", relation = "can_edit")
    public void editUser() { }
}
