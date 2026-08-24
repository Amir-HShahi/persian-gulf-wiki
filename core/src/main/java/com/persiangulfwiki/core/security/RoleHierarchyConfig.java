package com.persiangulfwiki.core.security;

import org.aopalliance.intercept.MethodInvocation;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.expression.method.DefaultMethodSecurityExpressionHandler;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;
import org.springframework.security.authorization.DefaultAuthorizationManagerFactory;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

@Configuration
@EnableMethodSecurity
public class RoleHierarchyConfig {

    // EXPERT_REVIEWER is deliberately absent — it's an orthogonal domain-trust flag,
    // not a rung on the ADMIN/MODERATOR ladder, so it must not inherit from or be
    // inherited by anything here.
    @Bean
    RoleHierarchy roleHierarchy() {
        return RoleHierarchyImpl.fromHierarchy("ROLE_ADMIN > ROLE_MODERATOR");
    }

    // A RoleHierarchy bean alone has no effect on @PreAuthorize/hasRole(...) checks —
    // method security builds its own DefaultMethodSecurityExpressionHandler that only
    // consults RoleHierarchy if one is explicitly configured on its authorization
    // manager factory.
    @Bean
    static DefaultMethodSecurityExpressionHandler methodSecurityExpressionHandler(RoleHierarchy roleHierarchy) {
        DefaultAuthorizationManagerFactory<MethodInvocation> authorizationManagerFactory =
                new DefaultAuthorizationManagerFactory<>();
        authorizationManagerFactory.setRoleHierarchy(roleHierarchy);

        DefaultMethodSecurityExpressionHandler handler = new DefaultMethodSecurityExpressionHandler();
        handler.setAuthorizationManagerFactory(authorizationManagerFactory);
        return handler;
    }
}
    