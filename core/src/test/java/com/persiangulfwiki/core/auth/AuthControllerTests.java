package com.persiangulfwiki.core.auth;

import tools.jackson.databind.ObjectMapper;
import com.persiangulfwiki.core.TestcontainersConfiguration;
import com.persiangulfwiki.core.auth.dto.LoginRequest;
import com.persiangulfwiki.core.auth.dto.RegisterRequest;
import com.persiangulfwiki.core.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class AuthControllerTests {

        @Autowired
        private MockMvc mockMvc;

        @Autowired
        private ObjectMapper objectMapper;

        @Autowired
        private UserRepository userRepository;

        @Test
        void registerCreatesAccountWithoutLeakingPassword() throws Exception {
                RegisterRequest request = new RegisterRequest("alice", "Alice@Example.com", "Correct-Horse1!");

                mockMvc.perform(post("/api/auth/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                                .andExpect(status().isCreated())
                                .andExpect(jsonPath("$.data.username").value("alice"))
                                .andExpect(jsonPath("$.data.email").value("alice@example.com"))
                                .andExpect(jsonPath("$.data.password").doesNotExist())
                                .andExpect(jsonPath("$.data.passwordHash").doesNotExist())
                                .andExpect(jsonPath("$.data.password_hash").doesNotExist());

                assertThat(userRepository.existsByEmail("alice@example.com")).isTrue();
        }

        @Test
        void registerMessageIsLocalizedToDefaultFarsiWithoutAcceptLanguageHeader() throws Exception {
                RegisterRequest request = new RegisterRequest("localedefault", "locale-default@example.com",
                                "Correct-Horse1!");

                mockMvc.perform(post("/api/auth/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                                .andExpect(status().isCreated())
                                .andExpect(jsonPath("$.message").value(
                                                "ثبت‌نام شما با موفقیت انجام شد. برای فعال‌سازی حساب کاربری، لطفاً ایمیل ارسالی را بررسی و آدرس ایمیل خود را تأیید فرمایید"));
        }

        @Test
        void registerMessageSwitchesLocaleWhenAcceptLanguageHeaderIsSet() throws Exception {
                RegisterRequest request = new RegisterRequest("localeenglish", "locale-english@example.com",
                                "Correct-Horse1!");

                mockMvc.perform(post("/api/auth/register")
                                .header("Accept-Language", "en")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                                .andExpect(status().isCreated())
                                .andExpect(jsonPath("$.message").value(
                                                "Your registration was completed successfully. To activate your account, please check the email we sent and verify your address."));
        }

        @Test
        void registerRejectsDuplicateEmail() throws Exception {
                RegisterRequest first = new RegisterRequest("bob", "bob@example.com", "Correct-Horse1!");
                mockMvc.perform(post("/api/auth/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(first)))
                                .andExpect(status().isCreated());

                RegisterRequest duplicate = new RegisterRequest("bob2", "BOB@example.com", "Another-Pass1!");
                mockMvc.perform(post("/api/auth/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(duplicate)))
                                .andExpect(status().isConflict());
        }

        @Test
        void registerRejectsInvalidFields() throws Exception {
                RegisterRequest invalid = new RegisterRequest("carol", "not-an-email", "short");

                mockMvc.perform(post("/api/auth/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(invalid)))
                                .andExpect(status().isBadRequest());
        }

        @Test
        void registerRejectsWeakPasswordWithPreciseMessage() throws Exception {
                RegisterRequest weakPassword = new RegisterRequest("dana", "dana@example.com", "alllowercase");

                mockMvc.perform(post("/api/auth/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(weakPassword)))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.errors[*].message").value(org.hamcrest.Matchers.hasItem(
                                                org.hamcrest.Matchers.allOf(
                                                                org.hamcrest.Matchers.containsString("حرف بزرگ"),
                                                                org.hamcrest.Matchers.containsString("رقم"),
                                                                org.hamcrest.Matchers.containsString("نویسه ویژه")))));

                assertThat(userRepository.existsByEmail("dana@example.com")).isFalse();
        }

        @Test
        void registerRejectsUsernameWithDisallowedCharacterWithPreciseMessage() throws Exception {
                RegisterRequest invalidUsername = new RegisterRequest("carol!", "carol@example.com", "Correct-Horse1!");

                mockMvc.perform(post("/api/auth/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(invalidUsername)))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.errors[*].message").value(org.hamcrest.Matchers.hasItem(
                                                org.hamcrest.Matchers.containsString("صرفاً شامل حروف انگلیسی، اعداد، زیرخط"))));

                assertThat(userRepository.existsByEmail("carol@example.com")).isFalse();
        }

        @Test
        void registerRejectsMalformedEmailWithPreciseMessage() throws Exception {
                RegisterRequest malformedEmail = new RegisterRequest("erin", "not-an-email", "Correct-Horse1!");

                mockMvc.perform(post("/api/auth/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(malformedEmail)))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.errors[*].message").value(org.hamcrest.Matchers.hasItem(
                                                org.hamcrest.Matchers.containsString("دارای علامت @ برای جداسازی بخش کاربری از دامنه باشد"))));

                assertThat(userRepository.existsByUsername("erin")).isFalse();
        }

        @Test
        void registerRejectsEmailWithMultipleAtSignsWithPreciseMessage() throws Exception {
                RegisterRequest multipleAtSigns = new RegisterRequest("frank", "a@@b.com", "Correct-Horse1!");

                mockMvc.perform(post("/api/auth/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(multipleAtSigns)))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.errors[*].message").value(org.hamcrest.Matchers.hasItem(
                                                org.hamcrest.Matchers.containsString("دقیقاً یک علامت @"))));

                assertThat(userRepository.existsByUsername("frank")).isFalse();
        }

        @Test
        void loginSetsAccessTokenCookieAndOmitsTokenFromBody() throws Exception {
                RegisterRequest register = new RegisterRequest("dave", "dave@example.com", "Correct-Horse1!");
                mockMvc.perform(post("/api/auth/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(register)))
                                .andExpect(status().isCreated());

                LoginRequest login = new LoginRequest("dave@example.com", "Correct-Horse1!");

                mockMvc.perform(post("/api/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(login)))
                                .andExpect(status().isOk())
                                .andExpect(cookie().exists("access_token"))
                                .andExpect(cookie().httpOnly("access_token", true))
                                .andExpect(content().string(not(org.hamcrest.Matchers.containsString("."))));
        }

        @Test
        void loginRejectsWrongPasswordAndNonexistentEmailIdentically() throws Exception {
                RegisterRequest register = new RegisterRequest("erin", "erin@example.com", "Correct-Horse1!");
                mockMvc.perform(post("/api/auth/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(register)))
                                .andExpect(status().isCreated());

                String wrongPasswordBody = mockMvc.perform(post("/api/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(
                                                new LoginRequest("erin@example.com", "wrong-pass"))))
                                .andExpect(status().isUnauthorized())
                                .andReturn().getResponse().getContentAsString();

                String noSuchUserBody = mockMvc.perform(post("/api/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(
                                                new LoginRequest("nobody@example.com", "whatever"))))
                                .andExpect(status().isUnauthorized())
                                .andReturn().getResponse().getContentAsString();

                // timestamp/traceId are per-request and expected to differ even for identical
                // errors; only what an attacker could actually compare across requests matters
                // for the enumeration-oracle check.
                assertThat(withoutPerRequestFields(wrongPasswordBody)).isEqualTo(withoutPerRequestFields(noSuchUserBody));
                assertThat(wrongPasswordBody).isNotBlank();
        }

        private java.util.Map<String, Object> withoutPerRequestFields(String body) {
                java.util.Map<String, Object> map = objectMapper.readValue(body, java.util.Map.class);
                map.remove("timestamp");
                map.remove("traceId");
                return map;
        }

        @Test
        void csrfEndpointSetsReadableCookie() throws Exception {
                String cookieValue = mockMvc.perform(get("/api/auth/csrf"))
                                .andExpect(status().isNoContent())
                                .andExpect(cookie().exists("XSRF-TOKEN"))
                                .andExpect(cookie().httpOnly("XSRF-TOKEN", false))
                                .andReturn().getResponse().getCookie("XSRF-TOKEN").getValue();

                assertThat(cookieValue).isNotBlank();
        }
}
