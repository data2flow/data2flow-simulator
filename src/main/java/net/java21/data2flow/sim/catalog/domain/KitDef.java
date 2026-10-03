package net.java21.data2flow.sim.catalog.domain;

import java.util.List;

/**
 * 가상 기기 묶음(SIM-09.07, {@code kits}).
 *
 * @param key                    키(예: {@code classroom-standard})
 * @param name                   이름
 * @param items                  배치할 유형과 수
 * @param suggestedFlowTemplates 함께 제안하는 플로우 템플릿 키(FLW-01.05)
 */
public record KitDef(String key, String name, List<Item> items, List<String> suggestedFlowTemplates) {

    public KitDef {
        items = List.copyOf(items);
        suggestedFlowTemplates = suggestedFlowTemplates == null ? List.of() : List.copyOf(suggestedFlowTemplates);
    }

    public int deviceCount() {
        return items.stream().mapToInt(Item::count).sum();
    }

    /**
     * @param typeKey    유형 키
     * @param count      대수
     * @param namePrefix 이름 접두어
     * @param relation   공간과의 관계 MEASURES(센서)·CONTROLS(장비) — DEV-01.05
     */
    public record Item(String typeKey, int count, String namePrefix, String relation) {
    }
}
