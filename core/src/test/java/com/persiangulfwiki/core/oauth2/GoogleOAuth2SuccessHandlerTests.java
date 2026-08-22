package com.persiangulfwiki.core.oauth2;

import com.persiangulfwiki.core.TestcontainersConfiguration;
import com.persiangulfwiki.core.user.entity.Role;
import com.persiangulfwiki.core.user.entity.User;
import com.persiangulfwiki.core.user.entity.UserRole;
import com.persiangulfwiki.core.user.repository.UserRepository;
import com.persiangulfwiki.core.user.repository.UserRoleRepository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class GoogleOAuth2SuccessHandlerTests {

    @Autowired
    private GoogleOAuth2SuccessHandler successHandler;

    @Autowired
    private GoogleOAuth2FailureHandler failureHandler;

    @Autowired
    private UserRepository userRepository;

    @MockitoSpyBean
    private UserRoleRepository userRoleRepository;

    @MockitoBean
    private JavaMailSender javaMailSender;

    @Value("${app.oauth2.success-redirect-url}")
    private String successRedirectUrl;

    @Value("${app.oauth2.pending-password-redirect-url}")
    private String pendingPasswordRedirectUrl;

    @Value("${app.oauth2.failure-redirect-url}")
    private String failureRedirectUrl;

    private static final List<GrantedAuthority> AUTHORITIES = List.of(new SimpleGrantedAuthority("OIDC_USER"));

    private OidcUser oidcUser(String sub, String email) {
        Instant issuedAt = Instant.now();
        OidcIdToken idToken = new OidcIdToken("id-token-value", issuedAt, issuedAt.plusSeconds(3600),
                Map.of("sub", sub, "email", email));
        return new DefaultOidcUser(AUTHORITIES, idToken);
    }

    private void invoke(String sub, String email, MockHttpServletResponse response) throws Exception {
        OAuth2AuthenticationToken authentication = new OAuth2AuthenticationToken(oidcUser(sub, email), AUTHORITIES,
                "google");
        successHandler.onAuthenticationSuccess(new MockHttpServletRequest(), response, authentication);
    }

    @Test
    void returningGoogleUserGetsRealSessionAndRedirectsToSuccess() throws Exception {
        String email = "returning@example.com";
        String googleSub = UUID.randomUUID().toString();

        User user = User.builder()
                .username("returninguser")
                .email(email)
                .passwordHash("already-set-hash")
                .googleSub(googleSub)
                .emailVerified(true)
                .build();
        userRepository.save(user);

        MockHttpServletResponse response = new MockHttpServletResponse();
        invoke(googleSub, email, response);

        assertThat(response.getCookie("access_token")).isNotNull();
        assertThat(response.getCookie("refresh_token")).isNotNull();
        assertThat(response.getRedirectedUrl()).isEqualTo(successRedirectUrl);
    }

    @Test
    void existingPasswordUserWithMatchingEmailAutoLinksAndNotifies() throws Exception {
        String email = "autolink@example.com";
        String googleSub = UUID.randomUUID().toString();

        User user = User.builder()
                .username("autolinkuser")
                .email(email)
                .passwordHash("already-set-hash")
                .emailVerified(true)
                .build();
        user = userRepository.save(user);

        MockHttpServletResponse response = new MockHttpServletResponse();
        invoke(googleSub, email, response);

        User linked = userRepository.findById(user.getId()).orElseThrow();
        assertThat(linked.getGoogleSub()).isEqualTo(googleSub);

        verify(javaMailSender, timeout(2000).times(1)).send(any(SimpleMailMessage.class));

        assertThat(response.getCookie("access_token")).isNotNull();
        assertThat(response.getCookie("refresh_token")).isNotNull();
        assertThat(response.getRedirectedUrl()).isEqualTo(successRedirectUrl);
    }

    @Test
    void abandonedGoogleOnlySignupRetryDoesNotDuplicateAndGetsPendingSession() throws Exception {
        String email = "abandoned@example.com";
        String googleSub = UUID.randomUUID().toString();

        User user = User.builder()
                .email(email)
                .passwordHash(null)
                .emailVerified(true)
                .build();
        user = userRepository.save(user);

        MockHttpServletResponse response = new MockHttpServletResponse();
        invoke(googleSub, email, response);

        UUID originalUserId = user.getId();
        assertThat(userRepository.findByEmail(email)).hasValueSatisfying(
                found -> assertThat(found.getId()).isEqualTo(originalUserId));

        assertThat(response.getCookie("access_token")).isNotNull();
        assertThat(response.getCookie("refresh_token")).isNull();
        assertThat(response.getRedirectedUrl()).isEqualTo(pendingPasswordRedirectUrl);

        verify(javaMailSender, after(500).never()).send(any(SimpleMailMessage.class));
    }

    @Test
    void brandNewEmailCreatesPendingUserWithContributorRole() throws Exception {
        String email = "brandnew@example.com";
        String googleSub = UUID.randomUUID().toString();

        MockHttpServletResponse response = new MockHttpServletResponse();
        invoke(googleSub, email, response);

        User created = userRepository.findByEmail(email).orElseThrow();
        assertThat(created.getPasswordHash()).isNull();
        assertThat(created.isEmailVerified()).isTrue();
        assertThat(created.getGoogleSub()).isEqualTo(googleSub);

        List<UserRole> roles = userRoleRepository.findByUserId(created.getId());
        assertThat(roles).extracting(UserRole::getRole).containsExactly(Role.CONTRIBUTOR);

        assertThat(response.getCookie("access_token")).isNotNull();
        assertThat(response.getCookie("refresh_token")).isNull();
        assertThat(response.getRedirectedUrl()).isEqualTo(pendingPasswordRedirectUrl);
    }

    @Test
    void exceptionDuringNewUserCreationRedirectsToFailureUrlAndLeavesNoPartialUserRow() throws Exception {
        String email = "partial-failure@example.com";
        String googleSub = UUID.randomUUID().toString();

        doThrow(new RuntimeException("simulated DB failure")).when(userRoleRepository).save(any());

        MockHttpServletResponse response = new MockHttpServletResponse();
        invoke(googleSub, email, response);

        assertThat(response.getRedirectedUrl()).isEqualTo(failureRedirectUrl);
        assertThat(response.getCookie("access_token")).isNull();
        assertThat(userRepository.findByEmail(email)).isEmpty();
    }

    @Test
    void authenticationFailureRedirectsToFailureUrl() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        OAuth2AuthenticationException exception = new OAuth2AuthenticationException(
                new OAuth2Error("access_denied"), "access_denied");

        failureHandler.onAuthenticationFailure(new MockHttpServletRequest(), response, exception);

        assertThat(response.getRedirectedUrl()).isEqualTo(failureRedirectUrl);
    }
}
