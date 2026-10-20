package com.persiangulfwiki.core.dev;

import com.persiangulfwiki.core.subject.service.SubjectInfoboxCacheService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

// The fixture routes write rows directly, past the services that clear the cached infoboxes on a
// real write (see SubjectInfoboxCacheService). Without this an E2E suite could mint a verification
// or delete a test subject and still be served the old infobox. So every non-GET request under
// /api/dev/** clears the cache once it completes: cruder than evicting per route, but no fixture
// route, present or future, can forget to.
@Configuration
@Profile("dev")
@RequiredArgsConstructor
public class DevFixtureCacheConfig implements WebMvcConfigurer {

    private final SubjectInfoboxCacheService subjectInfoboxCacheService;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {

            // After the handler, so after its transaction has committed.
            @Override
            public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
                    @Nullable Exception ex) {
                if (!HttpMethod.GET.matches(request.getMethod())) {
                    subjectInfoboxCacheService.evictAll();
                }
            }
        }).addPathPatterns("/api/dev/**");
    }
}
