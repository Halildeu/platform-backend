# variant.api.md — v1 & Legacy

## v1 REST Path (preferred)

- `GET /api/v1/variants?gridId=` — returns `PagedResultDto<VariantDto>`
- `POST /api/v1/variants` — body `CreateVariantRequestDto`, returns `VariantDto`
- `PUT /api/v1/variants/{variantId}` — body `UpdateVariantRequestDto`, returns `VariantDto`
- `PATCH /api/v1/variants/reorder` — body `ReorderVariantsRequestDto`
- `PATCH /api/v1/variants/{variantId}/preference` — body `VariantPreferenceUpdateRequestDto`, returns `VariantDto`
- `POST /api/v1/variants/{variantId}/clone` — body opsiyonel `CloneVariantRequestDto`, returns `VariantDto`
- `DELETE /api/v1/variants/{variantId}`

### DTO Özet

- `VariantDto` — id, gridId, name, isDefault, isGlobal, isGlobalDefault, state, schemaVersion, isCompatible, sortOrder, createdAt, updatedAt, isUserDefault, isUserSelected  
- Zarf: `PagedResultDto<T>` — items, total, page, pageSize  
- Hata: `ErrorResponse` (STYLE-API-001) — error, message, fieldErrors?, meta.traceId

## Legacy (Deprecated, kaldırılacak)

- `/api/variants` altındaki mevcut uçlar (list/create/update/reorder/preference/clone/delete)

Yeni geliştirmelerde v1 path’lerini kullanın; legacy uçlar geriye dönük uyumluluk için tutulacaktır.
