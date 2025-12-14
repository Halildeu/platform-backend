package com.example.variant.service;

import com.example.variant.dto.CloneVariantRequest;
import com.example.variant.dto.CreateVariantRequest;
import com.example.variant.dto.ReorderVariantsRequest;
import com.example.variant.dto.UpdateVariantRequest;
import com.example.variant.dto.VariantPreferenceUpdateRequest;
import com.example.variant.dto.VariantResponse;
import com.example.variant.model.GridVariant;
import com.example.variant.model.GridVariantPreference;
import com.example.variant.model.PreferenceType;
import com.example.variant.repository.GridVariantPreferenceRepository;
import com.example.variant.repository.GridVariantRepository;
import com.example.variant.security.AuthenticatedUser;
import com.example.commonauth.PermissionCodes;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class VariantService {

    private static final Logger log = LoggerFactory.getLogger(VariantService.class);
    private static final String MANAGE_GLOBAL_VARIANTS_PERMISSION = PermissionCodes.MANAGE_GLOBAL_VARIANTS;

    private final GridVariantRepository repository;
    private final GridVariantPreferenceRepository preferenceRepository;
    private final ObjectMapper objectMapper;

    public VariantService(GridVariantRepository repository,
                          GridVariantPreferenceRepository preferenceRepository,
                          ObjectMapper objectMapper) {
        this.repository = repository;
        this.preferenceRepository = preferenceRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<VariantResponse> getVariants(AuthenticatedUser user, String gridId) {
        enforceGridScope(user, gridId);
        List<GridVariant> variants = user.isAdmin()
                ? repository.findAllByGridIdOrdered(gridId)
                : (user.hasPermission(PermissionCodes.MANAGE_GLOBAL_VARIANTS)
                    ? repository.findVisibleVariants(user.id(), gridId)
                    : repository.findPersonalVariants(user.id(), gridId));

        Map<UUID, GridVariantPreference> preferenceMap = preferenceRepository.findAllByUserAndGrid(user.id(), gridId)
                .stream()
                .collect(Collectors.toMap(pref -> pref.getVariant().getId(), pref -> pref));

        return variants.stream()
                .map(variant -> toResponse(variant, user.id(), preferenceMap.get(variant.getId())))
                .toList();
    }

    @Transactional
    public VariantResponse createVariant(AuthenticatedUser user, CreateVariantRequest request) {
        if (request.isGlobalDefault() && !user.isAdmin()) {
            log.warn("Global varsayılan atama reddedildi: userId={} role={} gridId={}",
                    user.id(), user.role(), request.getGridId());
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Global varsayılan sadece adminler tarafından ayarlanabilir.");
        }

        GridVariant variant = new GridVariant();
        variant.setId(UUID.randomUUID());
        variant.setUserId(user.id());
        variant.setGridId(request.getGridId());
        variant.setName(request.getName());
        boolean makeGlobal = request.isGlobal() || request.isGlobalDefault();
        variant.setGlobal(makeGlobal);
        variant.setGlobalDefault(request.isGlobalDefault());
        variant.setDefault(request.isDefault());
        variant.setSchemaVersion(request.getSchemaVersion());
        variant.setStateJson(toJson(request.getState()));
        variant.setCompatible(true);
        variant.setSortOrder(resolveNextSortOrder(user.id(), request.getGridId(), variant.isGlobal()));

        if (variant.isGlobalDefault()) {
            repository.clearGlobalDefaultForGrid(request.getGridId(), null);
        }

        if (variant.isDefault()) {
            repository.clearDefaultForGrid(user.id(), request.getGridId(), null);
        }

        try {
            GridVariant saved = repository.save(variant);
            return toResponse(saved, user.id(), null);
        } catch (DataIntegrityViolationException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Aynı isimde bir varyant zaten mevcut.", ex);
        }
    }

    @Transactional
    public VariantResponse updateVariant(AuthenticatedUser user, UUID variantId, UpdateVariantRequest request) {
        if (request.getGlobalDefault() != null && !user.isAdmin()) {
            log.warn("Global varsayılan güncellemesi reddedildi: userId={} role={} variantId={}",
                    user.id(), user.role(), variantId);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Global varsayılan sadece adminler tarafından güncellenebilir.");
        }

        GridVariant variant = findAccessibleVariant(user, variantId);
        Long ownerId = variant.getUserId();

        if (variant.isGlobal()) {
            ensureCanManageGlobalVariants(user);
        }

        if (request.getName() != null && !request.getName().isBlank()) {
            variant.setName(request.getName());
        }

        if (request.getState() != null) {
            variant.setStateJson(toJson(request.getState()));
        }

        if (request.getSchemaVersion() != null) {
            variant.setSchemaVersion(request.getSchemaVersion());
        }

        boolean wasGlobal = variant.isGlobal();

        if (request.getGlobal() != null) {
            boolean makeGlobal = request.getGlobal();
            if (makeGlobal || wasGlobal) {
                ensureCanManageGlobalVariants(user);
            }
            variant.setGlobal(makeGlobal);
            if (!makeGlobal) {
                variant.setGlobalDefault(false);
                preferenceRepository.deleteAllByVariantId(variant.getId());
            }
        }

        if (request.getGlobalDefault() != null) {
            boolean makeGlobalDefault = request.getGlobalDefault();
             if (makeGlobalDefault || wasGlobal) {
                 ensureCanManageGlobalVariants(user);
             }
            variant.setGlobalDefault(makeGlobalDefault);
            if (makeGlobalDefault) {
                variant.setGlobal(true);
                repository.clearGlobalDefaultForGrid(variant.getGridId(), variant.getId());
            }
        }

        if (request.getDefault() != null) {
            boolean makeDefault = request.getDefault();
            variant.setDefault(makeDefault);
            if (variant.isDefault()) {
                repository.clearDefaultForGrid(ownerId, variant.getGridId(), variant.getId());
                if (!variant.isGlobal()) {
                    preferenceRepository.deleteByUserAndGridAndType(ownerId, variant.getGridId(), PreferenceType.GLOBAL_DEFAULT_OVERRIDE);
                }
            }
        }

        if (variant.isGlobal() && !wasGlobal) {
            variant.setSortOrder(resolveNextSortOrder(ownerId, variant.getGridId(), true));
        } else if (!variant.isGlobal() && wasGlobal) {
            variant.setSortOrder(resolveNextSortOrder(ownerId, variant.getGridId(), false));
        }

        variant.setCompatible(true);

        try {
            GridVariant saved = repository.save(variant);
            GridVariantPreference preference = preferenceRepository
                    .findByUserIdAndGridIdAndPreferenceType(user.id(), saved.getGridId(), PreferenceType.PERSONAL_DEFAULT)
                    .orElse(null);
            return toResponse(saved, user.id(), preference);
        } catch (DataIntegrityViolationException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Aynı isimde bir varyant zaten mevcut.", ex);
        }
    }

    @Transactional
    public void deleteVariant(AuthenticatedUser user, UUID variantId) {
        GridVariant variant = findAccessibleVariant(user, variantId);
        preferenceRepository.deleteAllByVariantId(variantId);
        repository.delete(variant);
    }

    @Transactional
    public void reorderVariants(AuthenticatedUser user, ReorderVariantsRequest request) {
        if (request.getItems() == null || request.getItems().isEmpty()) {
            return;
        }

        String gridId = request.getGridId();
        if (gridId == null || gridId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "gridId alanı zorunludur.");
        }

        List<UUID> ids = request.getItems().stream()
                .map(ReorderVariantsRequest.Item::getId)
                .toList();

        if (ids.isEmpty()) {
            return;
        }

        List<GridVariant> variants = repository.findAllById(ids);
        boolean isAdmin = user.isAdmin();

        if (variants.size() != ids.size()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Bazı varyant kayıtları bulunamadı.");
        }

        var orderMap = request.getItems().stream()
                .collect(Collectors.toMap(
                        ReorderVariantsRequest.Item::getId,
                        ReorderVariantsRequest.Item::getSortOrder,
                        (first, second) -> second));

        if (orderMap.size() != ids.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tekrarlı varyant kayıtları gönderildi.");
        }

        for (GridVariant variant : variants) {
            if (!variant.getGridId().equals(gridId)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tüm varyantlar aynı gridId değerine sahip olmalıdır.");
            }

            boolean isOwner = variant.getUserId() != null && variant.getUserId().equals(user.id());
            if (!variant.isGlobal() && !(isOwner || isAdmin)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Varyant üzerinde yetkiniz bulunmuyor.");
            }

            if (variant.isGlobal() && !isAdmin) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Global varyantları yeniden sıralamak için yetkiniz bulunmuyor.");
            }

            Integer newOrder = orderMap.get(variant.getId());
            if (newOrder == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Eksik sortOrder bilgisi.");
            }
            if (newOrder < 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sortOrder değeri negatif olamaz.");
            }
            variant.setSortOrder(newOrder);
        }

        repository.saveAll(variants);
    }

    @Transactional
    public VariantResponse updateVariantPreference(AuthenticatedUser user, UUID variantId, VariantPreferenceUpdateRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "İstek gövdesi boş olamaz.");
        }

        GridVariant variant = findVariantForPreference(variantId);

        log.debug("[VariantPreference] Processing change userId={} variantId={} default={} selected={} variantGlobal={} existingDefault={}",
                user.id(), variantId, request.getDefault(), request.getSelected(), variant.isGlobal(), variant.isDefault());

        PreferenceType preferenceType = variant.isGlobal()
                ? PreferenceType.GLOBAL_DEFAULT_OVERRIDE
                : PreferenceType.PERSONAL_DEFAULT;

        boolean makeDefault = Boolean.TRUE.equals(request.getDefault());
        Boolean selected = request.getSelected();

        if (!variant.isGlobal()) {
            if (variant.getUserId() == null || !variant.getUserId().equals(user.id())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Bu kişisel varyant üzerinde yetkiniz yok.");
            }

            if (!makeDefault && (selected == null || !selected)) {
                repository.clearDefaultForGrid(user.id(), variant.getGridId(), variant.getId());
                variant.setDefault(false);
                GridVariant saved = repository.save(variant);
                preferenceRepository.deleteByUserAndGridAndType(user.id(), saved.getGridId(), PreferenceType.GLOBAL_DEFAULT_OVERRIDE);
                preferenceRepository.deleteByUserAndGridAndType(user.id(), saved.getGridId(), PreferenceType.PERSONAL_DEFAULT);
                return toResponse(saved, user.id(), null);
            }

            if (makeDefault) {
                repository.clearDefaultForGrid(user.id(), variant.getGridId(), variant.getId());
                variant.setDefault(true);
                preferenceRepository.deleteByUserAndGridAndType(user.id(), variant.getGridId(), PreferenceType.GLOBAL_DEFAULT_OVERRIDE);
            } else {
                variant.setDefault(false);
            }

            GridVariant saved = repository.save(variant);

            GridVariantPreference preference = preferenceRepository
                    .findByUserIdAndGridIdAndPreferenceType(user.id(), saved.getGridId(), PreferenceType.PERSONAL_DEFAULT)
                    .orElseGet(() -> {
                        GridVariantPreference pref = new GridVariantPreference();
                        pref.setId(UUID.randomUUID());
                        pref.setGridId(saved.getGridId());
                        pref.setUserId(user.id());
                        pref.setPreferenceType(PreferenceType.PERSONAL_DEFAULT);
                        return pref;
                    });

            preference.setVariant(saved);
            preference.setDefault(makeDefault);
            preference.setSelected(selected != null ? selected : makeDefault);

            GridVariantPreference savedPreference = preferenceRepository.save(preference);
            log.debug("[VariantPreference] Stored personal preference userId={} variantId={} default={} selected={}",
                    user.id(), variantId, savedPreference.isDefault(), savedPreference.isSelected());
            return toResponse(saved, user.id(), savedPreference);
        }

        GridVariantPreference preference = preferenceRepository
                .findByUserIdAndGridIdAndPreferenceType(user.id(), variant.getGridId(), preferenceType)
                .orElseGet(() -> {
                    GridVariantPreference pref = new GridVariantPreference();
                    pref.setId(UUID.randomUUID());
                    pref.setGridId(variant.getGridId());
                    pref.setUserId(user.id());
                    pref.setPreferenceType(preferenceType);
                    return pref;
                });

        if (makeDefault) {
            repository.clearDefaultForGrid(user.id(), variant.getGridId(), null);
            preferenceRepository.deleteByUserAndGridAndType(user.id(), variant.getGridId(), PreferenceType.PERSONAL_DEFAULT);
        }

        if (!makeDefault && (selected == null || !selected)) {
            if (preference.getId() != null) {
                preferenceRepository.deleteByUserAndGridAndType(user.id(), variant.getGridId(), preferenceType);
            }
            return toResponse(variant, user.id(), null);
        }

        preference.setVariant(variant);
        preference.setDefault(makeDefault);
        if (selected != null) {
            preference.setSelected(selected);
        } else if (makeDefault) {
            preference.setSelected(true);
        }

        GridVariantPreference savedPreference = preferenceRepository.save(preference);
        log.debug("[VariantPreference] Stored preference userId={} variantId={} default={} selected={}",
                user.id(), variantId, savedPreference.isDefault(), savedPreference.isSelected());
        return toResponse(variant, user.id(), savedPreference);
    }

    @Transactional
    public VariantResponse cloneGlobalVariantToPersonal(AuthenticatedUser user, UUID variantId, CloneVariantRequest request) {
        GridVariant source = repository.findById(variantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Kaynak varyant bulunamadı."));

        if (!source.isGlobal()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Yalnızca global varyantlar kişisel kopyaya dönüştürülebilir.");
        }

        String name = (request != null && request.getName() != null && !request.getName().isBlank())
                ? request.getName().trim()
                : source.getName();

        boolean setDefault = request != null && Boolean.TRUE.equals(request.getSetDefault());
       boolean setSelected = request != null && Boolean.TRUE.equals(request.getSetSelected());

        GridVariant personal = new GridVariant();
        personal.setId(UUID.randomUUID());
        personal.setUserId(user.id());
        personal.setGridId(source.getGridId());
        personal.setName(name);
        personal.setGlobal(false);
        personal.setGlobalDefault(false);
        personal.setDefault(setDefault);
        personal.setSchemaVersion(source.getSchemaVersion());
        personal.setStateJson(source.getStateJson());
        personal.setCompatible(source.isCompatible());
        personal.setSortOrder(resolveNextSortOrder(user.id(), source.getGridId(), false));

        if (setDefault) {
            repository.clearDefaultForGrid(user.id(), personal.getGridId(), null);
        }

        GridVariant saved = repository.save(personal);

        GridVariantPreference preference = null;
        if (setSelected || setDefault) {
            preference = preferenceRepository.findByUserIdAndVariant_Id(user.id(), saved.getId())
                    .orElseGet(() -> {
                        GridVariantPreference pref = new GridVariantPreference();
                        pref.setId(UUID.randomUUID());
                        pref.setGridId(saved.getGridId());
                        pref.setUserId(user.id());
                        pref.setVariant(saved);
                        pref.setPreferenceType(PreferenceType.PERSONAL_DEFAULT);
                        return pref;
                    });
            preference.setSelected(setSelected || setDefault);
            preference.setDefault(false);
            preference = preferenceRepository.save(preference);
        }

        return toResponse(saved, user.id(), preference);
    }

    private VariantResponse toResponse(GridVariant variant, Long currentUserId, GridVariantPreference preference) {
        JsonNode stateNode;
        try {
            String stateJson = variant.getStateJson();
            if (stateJson == null || stateJson.isBlank()) {
                stateNode = objectMapper.createObjectNode();
            } else {
                stateNode = objectMapper.readTree(stateJson);
            }
        } catch (JsonProcessingException e) {
            log.warn("Invalid state_json for variantId={} gridId={} - falling back to empty object", variant.getId(), variant.getGridId(), e);
            stateNode = objectMapper.createObjectNode();
        }

        boolean isUserDefault = false;
        boolean isUserSelected = false;

        if (variant.isGlobal()) {
            if (preference != null) {
                isUserDefault = preference.isDefault();
                isUserSelected = preference.isSelected();
            }
        } else if (currentUserId != null && variant.getUserId() != null && variant.getUserId().equals(currentUserId)) {
            isUserDefault = variant.isDefault();
            isUserSelected = preference != null ? preference.isSelected() : variant.isDefault();
        } else if (preference != null) {
            isUserDefault = preference.isDefault();
            isUserSelected = preference.isSelected();
        }

        return new VariantResponse(
                variant.getId(),
                variant.getGridId(),
                variant.getName(),
                variant.isDefault(),
                variant.isGlobal(),
                variant.isGlobalDefault(),
                stateNode,
                variant.getSchemaVersion(),
                variant.isCompatible(),
                variant.getSortOrder(),
                variant.getCreatedAt(),
                variant.getUpdatedAt(),
                isUserDefault,
                isUserSelected
        );
    }

    private String toJson(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Geçersiz varyant state", e);
        }
    }

    private int resolveNextSortOrder(Long userId, String gridId, boolean isGlobal) {
        Integer maxSortOrder = isGlobal
                ? repository.findMaxSortOrderForGlobal(gridId)
                : repository.findMaxSortOrderForUser(userId, gridId);

        if (maxSortOrder == null || maxSortOrder < 0) {
            return 0;
        }
        return maxSortOrder + 1;
    }

    private GridVariant findAccessibleVariant(AuthenticatedUser user, UUID variantId) {
        GridVariant variant = repository.findById(variantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Varyant bulunamadı"));

        if (variant.isGlobal()) {
            if (user.isAdmin() || user.hasPermission(MANAGE_GLOBAL_VARIANTS_PERMISSION)) {
                return variant;
            }
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Global varyantları yönetme yetkiniz yok.");
        }

        if (variant.getUserId() != null && variant.getUserId().equals(user.id())) {
            return variant;
        }

        if (user.isAdmin()) {
            return variant;
        }

        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Varyant bulunamadı");
    }

    private GridVariant findVariantForPreference(UUID variantId) {
        return repository.findById(variantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Varyant bulunamadı veya erişim izniniz yok."));
    }

    private void ensureCanManageGlobalVariants(AuthenticatedUser user) {
        if (user.isAdmin()) {
            return;
        }
        if (user.hasPermission(MANAGE_GLOBAL_VARIANTS_PERMISSION)) {
            return;
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Global varyantları yönetme yetkiniz yok.");
    }

    private void enforceGridScope(AuthenticatedUser user, String gridId) {
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Kullanıcı bilgisi alınamadı");
        }
        if (user.isAdmin()) {
            return;
        }
        if (gridId == null || gridId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "gridId zorunludur");
        }
        List<String> allowed = user.allowedGridIds();
        if (allowed == null || allowed.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Scope izni yok");
        }
        String normalizedGridId = gridId.trim();
        if (!allowed.contains(normalizedGridId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Scope izni yok: " + normalizedGridId);
        }
    }
}
