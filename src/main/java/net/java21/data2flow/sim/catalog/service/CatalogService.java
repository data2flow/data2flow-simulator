package net.java21.data2flow.sim.catalog.service;

import net.java21.data2flow.contracts.concurrency.VersionCheck;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.sim.catalog.domain.BuiltinCatalog;
import net.java21.data2flow.sim.catalog.domain.DeviceTypeDef;
import net.java21.data2flow.sim.catalog.domain.KitDef;
import net.java21.data2flow.sim.catalog.domain.MetricDef;
import net.java21.data2flow.sim.catalog.domain.PropertyResolver;
import net.java21.data2flow.sim.catalog.domain.PropertyValidator;
import net.java21.data2flow.sim.catalog.repository.CatalogRepository;
import net.java21.data2flow.sim.catalog.repository.ProfileRepository;
import net.java21.data2flow.sim.common.SimErrorCode;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 가상 기기 카탈로그·키트·조직 프로필(SIM-09.01·09.02·09.03, API-SIM-02·08) */
@Service
public class CatalogService {

    private final CatalogRepository catalog;
    private final ProfileRepository profiles;

    public CatalogService(CatalogRepository catalog, ProfileRepository profiles) {
        this.catalog = catalog;
        this.profiles = profiles;
    }

    /** 시작할 때 기본 유형·키트를 DB에 맞춘다(조직 0, 멱등) */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void syncBuiltin() {
        BuiltinCatalog.types().forEach(catalog::upsertBuiltinType);
        BuiltinCatalog.kits().forEach(catalog::upsertBuiltinKit);
    }

    /** API-SIM-02 카탈로그 */
    public Map<String, Object> catalog(long organizationId, String category) {
        List<Map<String, Object>> types = new ArrayList<>();
        for (CatalogRepository.TypeRow t : catalog.findTypes(organizationId)) {
            if (category != null && !category.equals(t.def().category())) {
                continue;
            }
            types.add(typeView(t));
        }
        List<Map<String, Object>> kits = new ArrayList<>();
        for (KitDef k : BuiltinCatalog.kits()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", k.key());
            m.put("name", k.name());
            m.put("items", k.items());
            m.put("suggestedFlowTemplates", k.suggestedFlowTemplates());
            kits.add(m);
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("types", types);
        r.put("kits", kits);
        return r;
    }

    static Map<String, Object> typeView(CatalogRepository.TypeRow t) {
        DeviceTypeDef d = t.def();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", Long.toString(t.id()));
        m.put("key", d.key());
        m.put("name", d.name());
        m.put("category", d.category());
        m.put("builtin", t.builtin());
        m.put("metrics", d.metrics());
        m.put("capabilities", d.capabilities());
        m.put("propertyDefs", d.propertyDefs());
        m.put("physicsEffects", d.physicsEffects());
        m.put("linkedModelCode", d.linkedModelCode());
        m.put("defaultPayloadFormat", d.defaultPayloadFormat());
        m.put("defaultReportIntervalSec", d.defaultReportIntervalSec());
        List<String> summary = new ArrayList<>();
        if (!d.metrics().isEmpty()) {
            summary.add(String.join(", ", d.metrics().stream().map(MetricDef::key).toList()));
        }
        if (!d.capabilities().isEmpty()) {
            summary.add(String.join(", ", d.capabilities()));
        }
        d.propertyDefs().stream().limit(3).forEach(p -> summary.add(p.name() + " " + p.defaultValue() + (p.unit() == null ? "" : p.unit())));
        m.put("summary", summary);
        return m;
    }

    public CatalogRepository.TypeRow type(long organizationId, long typeId) {
        return catalog.findType(organizationId, typeId).orElseThrow(() -> new BusinessException(SimErrorCode.SIM_NOT_FOUND));
    }

    public CatalogRepository.TypeRow typeByKey(long organizationId, String key) {
        return catalog.findTypeByKey(organizationId, key).orElseThrow(() -> new BusinessException(SimErrorCode.SIM_NOT_FOUND,
                List.of(new FieldErrorDetail("typeKey", "NotFound", "없는 유형입니다: " + key))));
    }

    // ───────────── 프로필(API-SIM-08) ─────────────

    public record ProfileRequest(String name, Long typeId, Map<String, Object> overrides, Integer baseVersion) {
    }

    @Transactional
    public Map<String, Object> createProfile(long organizationId, Long userId, ProfileRequest req) {
        requireName(req.name());
        if (req.typeId() == null) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail("typeId", "NotNull", "유형이 필요합니다")));
        }
        CatalogRepository.TypeRow type = type(organizationId, req.typeId());
        Map<String, Object> overrides = PropertyResolver.merge(Map.of(), req.overrides());
        PropertyValidator.validate(type.def(), overrides, "overrides.");
        try {
            long id = profiles.insert(organizationId, req.name(), type.id(), overrides, userId);
            return profile(organizationId, id);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(CommonErrorCode.VERSION_CONFLICT,
                    List.of(new FieldErrorDetail("name", "DUPLICATE", "같은 이름의 프로필이 있습니다")));
        }
    }

    @Transactional
    public Map<String, Object> updateProfile(long organizationId, long id, Long userId, ProfileRequest req) {
        ProfileRepository.ProfileRow row = profiles.findById(organizationId, id)
                .orElseThrow(() -> new BusinessException(SimErrorCode.SIM_NOT_FOUND));
        requireName(req.name());
        if (req.baseVersion() == null) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST,
                    List.of(new FieldErrorDetail(VersionCheck.BASE_VERSION, "NotNull", "baseVersion이 필요합니다")));
        }
        VersionCheck.require(req.baseVersion(), row.version());
        CatalogRepository.TypeRow type = type(organizationId, row.typeId());
        Map<String, Object> overrides = PropertyResolver.merge(Map.of(), req.overrides());
        PropertyValidator.validate(type.def(), overrides, "overrides.");
        VersionCheck.requireUpdated(profiles.update(organizationId, id, req.name(), overrides, row.version(), userId));
        return profile(organizationId, id);
    }

    @Transactional
    public void deleteProfile(long organizationId, long id) {
        profiles.findById(organizationId, id).orElseThrow(() -> new BusinessException(SimErrorCode.SIM_NOT_FOUND));
        if (profiles.countDevices(organizationId, id) > 0) {
            throw new BusinessException(SimErrorCode.SIM_PROFILE_IN_USE);
        }
        profiles.delete(organizationId, id);
    }

    public List<Map<String, Object>> profiles(long organizationId) {
        return profiles.findAll(organizationId).stream().map(p -> profile(organizationId, p.id())).toList();
    }

    /** API-SIM-08 단건: 특성마다 값과 출처(CATALOG·PROFILE) */
    public Map<String, Object> profile(long organizationId, long id) {
        ProfileRepository.ProfileRow row = profiles.findById(organizationId, id)
                .orElseThrow(() -> new BusinessException(SimErrorCode.SIM_NOT_FOUND));
        CatalogRepository.TypeRow type = type(organizationId, row.typeId());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", Long.toString(row.id()));
        m.put("name", row.name());
        m.put("typeId", Long.toString(row.typeId()));
        m.put("properties", PropertyResolver.resolve(type.def(), row.overrides(), Map.of()));
        m.put("deviceCount", profiles.countDevices(organizationId, id));
        m.put("version", row.version());
        return m;
    }

    private static void requireName(String name) {
        if (name == null || name.isBlank() || name.length() > 80) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, List.of(new FieldErrorDetail("name", "Size", "1~80자")));
        }
    }
}
