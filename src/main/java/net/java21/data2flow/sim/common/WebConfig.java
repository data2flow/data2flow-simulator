package net.java21.data2flow.sim.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.contracts.identity.DataflowHeaders;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * 내부 API 규칙: {@code /internal/sim/**}는 서비스 사이 호출만 받는다(ADR-021: 토큰 없음, {@code X-CALLER-SERVICE} 표시 필수 —
 * 없으면 401). 조직은 {@link OrgId}로 꺼낸다.
 */
@Configuration(proxyBeanMethods = false)
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                String caller = request.getHeader(DataflowHeaders.CALLER_SERVICE);
                if (caller == null || caller.isBlank()) {
                    throw new BusinessException(CommonErrorCode.AUTH_TOKEN_INVALID,
                            List.of(new FieldErrorDetail(DataflowHeaders.CALLER_SERVICE, "NotBlank", "내부 호출 표시가 필요합니다")));
                }
                return true;
            }
        }).addPathPatterns("/internal/sim/**");
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new HandlerMethodArgumentResolver() {
            @Override
            public boolean supportsParameter(MethodParameter parameter) {
                return parameter.hasParameterAnnotation(OrgId.class);
            }

            @Override
            public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mav, NativeWebRequest request,
                                          WebDataBinderFactory binder) {
                String raw = request.getHeader(DataflowHeaders.ORG_ID);
                if (raw == null || raw.isBlank()) {
                    raw = request.getParameter("organizationId");
                }
                try {
                    long id = Long.parseLong(raw == null ? "" : raw.trim());
                    if (id > 0) {
                        return id;
                    }
                } catch (NumberFormatException ignored) {
                    // 아래에서 400
                }
                throw new BusinessException(CommonErrorCode.INVALID_REQUEST,
                        List.of(new FieldErrorDetail(DataflowHeaders.ORG_ID, "NotNull", "조직이 필요합니다(X-ORG-ID 또는 organizationId)")));
            }
        });
    }
}
