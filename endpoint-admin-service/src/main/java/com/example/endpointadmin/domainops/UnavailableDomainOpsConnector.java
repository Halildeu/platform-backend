package com.example.endpointadmin.domainops;

public class UnavailableDomainOpsConnector implements DomainOpsConnector {

    @Override
    public String name() {
        return "unavailable";
    }

    @Override
    public DomainOpsConnectorDispatchResult dispatch(DomainOpsConnectorDispatchRequest request) {
        return DomainOpsConnectorDispatchResult.failed("connector-unavailable");
    }
}
