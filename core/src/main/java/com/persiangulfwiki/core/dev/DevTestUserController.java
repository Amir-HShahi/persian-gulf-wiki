package com.persiangulfwiki.core.dev;

import com.persiangulfwiki.core.dev.dto.DevTestUserRequest;
import com.persiangulfwiki.core.dev.dto.DevTestUserResponse;
import com.persiangulfwiki.core.user.entity.Role;
import com.persiangulfwiki.core.user.exception.UserNotFoundException;
import com.persiangulfwiki.core.user.entity.User;
import com.persiangulfwiki.core.user.entity.UserRole;
import com.persiangulfwiki.core.user.repository.UserRepository;
import com.persiangulfwiki.core.user.repository.UserRoleRepository;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import lombok.RequiredArgsConstructor;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

// Mints a brand-new throwaway account per call and returns its credentials. This exists
// because DevUserSeeder's accounts are shared mutable state: fine for a human clicking around,
// but a parallel E2E suite in which one test disables moderator@dev.local while another is
// logged in as it produces flakes that look like application bugs. The fix is one user per
// test, not test ordering or a mutex.
//
// Two independent gates keep this out of production: @Profile("dev") here, so the bean does
// not exist, and DevSecurityConfig's profiled filter chain, so the path is not permitted. Both
// are needed — see the comment on DevSecurityConfig for why the route is not simply added to
// SecurityConfig's permitAll list.
//
// Deliberately does not route through AuthService.register. That path normalizes the email,
// applies duplicate rules, and fires sendVerification — every test user would produce an async
// Mailpit message and land unverified, and states like "enabled=false" would be unreachable.
// Writing the User directly, exactly as DevUserSeeder does, is what makes arbitrary states
// possible.
@RestController
@RequestMapping("/api/dev/test-users")
@Profile("dev")
@RequiredArgsConstructor
@Tag(name = "Dev Test Users", description = "Dev-profile-only fixture endpoint for E2E suites. Not registered in any other profile.")
public class DevTestUserController {

    // 48 bits of the UUID, enough that parallel runs never collide in practice while keeping
    // the identifiers short enough to read in a failing test's output. Both username and email
    // derive from the same slug, and username is citext (User.java) — so collisions would be
    // case-insensitive — which is why the slug is generated rather than caller-supplied.
    private static final int SLUG_LENGTH = 12;

    // The generated password is checked by nothing on this path, but seeded accounts are
    // expected to survive password-change flows that re-validate against
    // PasswordConstraintValidator. This suffix guarantees the uppercase/lowercase/digit/special
    // rules hold no matter what the random segment happens to contain.
    private static final String PASSWORD_SUFFIX = "aA1!";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final PasswordEncoder passwordEncoder;

    @Operation(summary = "Mint a disposable test user", description = "Creates a new user with a generated username, email and password, and returns "
            + "the plaintext credentials. Every field of the request body is optional; the "
            + "default is a verified, enabled CONTRIBUTOR. Roles are applied literally, with "
            + "no implied grants.")
    @ApiResponse(responseCode = "201", description = "The created user, including its plaintext password.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    // Returns the DTO bare rather than wrapped in ApiResult, unlike every production
    // controller. There is no i18n message worth attaching for a machine-only caller, and a
    // test harness reading `body.password` directly is less to get wrong in `beforeEach`.
    public DevTestUserResponse mint(@RequestBody(required = false) DevTestUserRequest request) {
        DevTestUserRequest safeRequest = request != null
                ? request
                : new DevTestUserRequest(null, null, null);

        List<Role> roles = safeRequest.roles() == null || safeRequest.roles().isEmpty()
                ? List.of(Role.CONTRIBUTOR)
                : List.copyOf(safeRequest.roles());

        String slug = UUID.randomUUID().toString().replace("-", "").substring(0, SLUG_LENGTH);
        String password = generatePassword();

        User user = userRepository.save(User.builder()
                .username(DevTestUsers.USERNAME_PREFIX + slug)
                .email(DevTestUsers.EMAIL_PREFIX + slug + DevTestUsers.EMAIL_DOMAIN)
                .passwordHash(passwordEncoder.encode(password))
                .emailVerified(safeRequest.emailVerified() == null || safeRequest.emailVerified())
                .enabled(safeRequest.enabled() == null || safeRequest.enabled())
                .build());

        for (Role role : roles) {
            userRoleRepository.save(UserRole.builder()
                    .user(user)
                    .role(role)
                    .build());
        }

        // Nothing is logged here on purpose: the password must not reach a log file, and the
        // volume (one line per test) would drown the dev console anyway.
        return new DevTestUserResponse(user.getId(), user.getEmail(), user.getUsername(), password, roles);
    }

    @Operation(summary = "Delete a minted test user", description = "Deletes a user previously created by this endpoint. Refuses anything else — a "
            + "seeded DevUserSeeder account or a hand-made one is reported as 404 rather "
            + "than deleted. Idempotent: deleting an id twice returns 404 the second time.")
    @ApiResponse(responseCode = "204", description = "The user was deleted.")
    @ApiResponse(responseCode = "404", description = "No such user, or the id names an account this endpoint did not mint.")
    @DeleteMapping("/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void delete(@PathVariable UUID userId) {
        // Deliberately narrower than "delete whatever id I am given". A teardown hook that
        // loses track of which id it minted must not be able to remove moderator@dev.local
        // out from under the frontend developer using it — the prefix check is what makes
        // handing an arbitrary id to this route safe.
        User user = userRepository.findById(userId)
                .filter(candidate -> candidate.getEmail().startsWith(DevTestUsers.EMAIL_PREFIX))
                // Reuses the production exception rather than defining a dev-only one:
                // GlobalExceptionHandler already maps it to 404 USER_NOT_FOUND, so this adds
                // no handler, no message key, and nothing to keep in sync.
                .orElseThrow(UserNotFoundException::new);

        // Every table referencing users (id) is ON DELETE CASCADE or ON DELETE SET NULL — see
        // the V2/V3/V5/V6/V12/V13 migrations — so the row's roles, refresh tokens and
        // outstanding verification/reset tokens go with it without an explicit cleanup pass.
        userRepository.delete(user);
    }

    private String generatePassword() {
        byte[] entropy = new byte[12];
        RANDOM.nextBytes(entropy);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(entropy) + PASSWORD_SUFFIX;
    }
}
