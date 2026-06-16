package com.example.endpointadmin.domainops;

public interface DomainOpsConnector {

    String name();

    DomainOpsConnectorDispatchResult dispatch(DomainOpsConnectorDispatchRequest request);
}
