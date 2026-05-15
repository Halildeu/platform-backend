"""Typed contract models for the schema-service snapshot consumer.

The shape mirrors the schema-service ``GET /api/v1/schema/snapshot``
response. Only fields that the ETL worker actually consumes are
modelled here; unknown JSON keys are silently ignored so additive
backend changes do not break the worker.

Adım 12 PR-1 scope keeps the model deliberately minimal:

* table identity: ``schema`` + ``name``
* per-column metadata: ``name``, ``type``, ``nullable``
* allowlist provenance: ``name`` + ``version`` (matches the report-service
  ``ReportingAllowlist.V1`` discipline established in Adım 11.1)
* contract version: explicit string carried so the client can fail-closed
  on version mismatch (see
  :class:`etl_worker.schema_service_client.SchemaContractVersionMismatch`)

Future PRs may extend these dataclasses (e.g. column ``length``, table
``schema_mode``, parametric ``yearly_schemas`` list) — those additions
stay backwards compatible because the parser ignores unknown keys.
"""

from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True, slots=True)
class ColumnSpec:
    """One column inside a schema-service table snapshot."""

    name: str
    type: str
    nullable: bool


@dataclass(frozen=True, slots=True)
class TableSpec:
    """One table inside a schema-service snapshot.

    ``schema`` and ``name`` together uniquely identify the table within
    a snapshot. ``columns`` preserves the order returned by
    schema-service so downstream consumers (e.g. the future pyodbc
    SELECT generator) can rely on a stable layout.
    """

    schema: str
    name: str
    columns: tuple[ColumnSpec, ...]


@dataclass(frozen=True, slots=True)
class SchemaSnapshot:
    """Top-level schema-service contract response model.

    ``contract_version`` is a free-form string the client validates
    against the set of versions it supports
    (``SchemaServiceClient.__init__(supported_versions=...)``); unknown
    versions fail-closed with
    :class:`etl_worker.schema_service_client.SchemaContractVersionMismatch`
    so a backwards-incompatible backend change cannot silently corrupt
    an ETL run.

    ``allowlist_name`` + ``allowlist_version`` mirror the
    ``ReportingAllowlist.V1`` discipline from report-service (Adım 11.1):
    the worker must know exactly which named allowlist gated the
    snapshot it consumed, so audit logs and reports_db inserts can be
    traced back to a specific contract revision.
    """

    contract_version: str
    allowlist_name: str
    allowlist_version: str
    tables: tuple[TableSpec, ...]
