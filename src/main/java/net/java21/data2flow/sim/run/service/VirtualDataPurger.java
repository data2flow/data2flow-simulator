package net.java21.data2flow.sim.run.service;

import java.util.List;

/**
 * 끝난 실행의 가상 데이터(텔레메트리·원본·알람·명령 이력) 정리 요청(SIM-07.05·11.03). 데이터는 pipeline·core·action 스키마에 있으므로
 * 정리 작업(API-SIM-25, EVT-SIM-04)은 core-api가 한다. simulator는 기한이 지난 실행을 골라 요청만 한다.
 */
public interface VirtualDataPurger {

    /** @return 요청을 받아 주었으면 true(그 실행을 PURGED로 표시) */
    boolean requestPurge(long organizationId, List<Long> runIds);
}
