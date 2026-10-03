package net.java21.data2flow.sim.common;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 컨트롤러 매개변수: 요청 조직. core-api가 내부 호출에 실어 보내는 {@code X-ORG-ID}(gateway가 넣은 값을 그대로 전달) 또는 쿼리
 * {@code organizationId}. 없으면 400.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface OrgId {
}
