## Sıralama & Gelişmiş Filtre Uygulama Rehberi (Spring Data)

Amaç
- `sort=field,dir;field2,dir2` ve `advancedFilter` (URL‑encoded JSON) parametrelerini Spring Data JPA ile güvenli biçimde uygulamak.

Kod Kalıpları (özet)
1) Controller — Parametreler
```java
@RequestParam(value = "sort", required = false) String sort,
@RequestParam(value = "advancedFilter", required = false) String advancedFilter,
```

2) Sıralama — Çoklu ORDER BY
```java
Set<String> ALLOWED = Set.of("createDate", "lastLogin", "email", "name", "role", "enabled");
List<Sort.Order> orders = new ArrayList<>();
for (String p : sort.split(";")) {
  String[] fd = p.split(",");
  if (fd.length == 2 && ALLOWED.contains(fd[0])) {
    orders.add(new Sort.Order("desc".equals(fd[1])? Sort.Direction.DESC: Sort.Direction.ASC, fd[0]));
  }
}
Sort s = orders.isEmpty() ? Sort.by(Sort.Direction.DESC, "createDate") : Sort.by(orders);
Pageable pageable = PageRequest.of(page-1, pageSize, s);
```

3) Gelişmiş Filtre — Whitelist parse → Specification
```java
Specification<User> spec = null; // mevcut arama/status/role spec
Specification<User> extra = AdvancedFilterParser.parse(advancedFilter, ALLOWED);
if (extra != null) spec = (spec == null) ? extra : spec.and(extra);
```

4) Service — Overload ile extraSpec desteği
```java
public Page<User> searchUsers(String search, String status, String role, Specification<User> extraSpec, Pageable pageable)
```

Test Checklist
- sort param
  - `sort=email,asc;createDate,desc` sıralaması deterministik; bilinmeyen alan → 200 ama varsayılan ORDER BY
  - Bozuk format → varsayılan ORDER BY (`createDate desc`)
- advancedFilter
  - İzinli alan/operatör çalışır (equals/contains/inRange)
  - Bilinmeyen alan/operatör → 400 (veya şimdilik yok say → 200) — politika seçin ve doc’a yazın
  - Tip uyuşmazlığı/bozuk JSON → 400 (veya yok say) — politika seçin

Güvenlik Notları
- Whitelist zorunlu: alan/operatör listesi `docs/agents/06-advanced-filter-whitelist.md`
- Parametre bağlama (prepared statements) kullanın; serbest metin concatenation yapmayın

Bağlantılar
- `docs/03-delivery/api/users.api.md`
- `docs/agents/06-advanced-filter-whitelist.md`
