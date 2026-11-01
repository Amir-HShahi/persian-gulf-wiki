package com.persiangulfwiki.core.user.service;

import com.persiangulfwiki.core.article.dto.ArticleCardResponse;
import com.persiangulfwiki.core.article.entity.Article;
import com.persiangulfwiki.core.article.repository.ArticleRepository;
import com.persiangulfwiki.core.article.service.ArticleCardService;
import com.persiangulfwiki.core.common.dto.PageResponse;
import com.persiangulfwiki.core.user.dto.ContributedArticleResponse;
import com.persiangulfwiki.core.user.dto.ContributionType;
import com.persiangulfwiki.core.user.dto.PublicProfileResponse;
import com.persiangulfwiki.core.user.dto.UpdateProfileRequest;
import com.persiangulfwiki.core.user.dto.UserResponse;
import com.persiangulfwiki.core.user.entity.Role;
import com.persiangulfwiki.core.user.entity.User;
import com.persiangulfwiki.core.user.entity.UserRole;
import com.persiangulfwiki.core.user.exception.UserNotFoundException;
import com.persiangulfwiki.core.user.repository.ContributedArticleRow;
import com.persiangulfwiki.core.user.repository.UserRepository;
import com.persiangulfwiki.core.user.repository.UserRoleRepository;

import lombok.RequiredArgsConstructor;

import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class UserProfileService {

    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final ArticleRepository articleRepository;
    private final ArticleCardService articleCardService;
    private final AvatarService avatarService;

    @Transactional(readOnly = true)
    public UserResponse getOwnProfile(UUID userId) {
        return toOwnResponse(userRepository.findById(userId).orElseThrow(UserNotFoundException::new));
    }

    // Blank optional fields are stored as null, so "" and an omitted field mean the same thing.
    @Transactional
    public UserResponse updateProfile(UUID userId, UpdateProfileRequest request) {
        User user = userRepository.findById(userId).orElseThrow(UserNotFoundException::new);
        user.setFullName(request.fullName().strip());
        user.setBio(blankToNull(request.bio()));
        user.setAffiliation(blankToNull(request.affiliation()));
        user.replaceLinks(request.links() == null ? List.of() : request.links().stream().map(String::strip).toList());
        return toOwnResponse(userRepository.save(user));
    }

    @Transactional(readOnly = true)
    public UserResponse toOwnResponse(User user) {
        return new UserResponse(user.getId(), user.getUsername(), user.getEmail(), roleNames(user.getId()),
                user.getFullName(), avatarService.publicUrl(user.getAvatarKey()).orElse(null), user.getBio(),
                user.getAffiliation(), user.getLinks(), user.getCreatedAt(), user.isProfileComplete());
    }

    @Transactional(readOnly = true)
    public PublicProfileResponse getPublicProfile(String username) {
        User user = requirePublicUser(username);
        return new PublicProfileResponse(user.getUsername(), user.getFullName(),
                avatarService.publicUrl(user.getAvatarKey()).orElseThrow(UserNotFoundException::new), user.getBio(),
                user.getAffiliation(), user.getLinks(), user.getCreatedAt(), roleNames(user.getId()),
                userRepository.countContributions(user.getId()));
    }

    // Newest contribution first; each article once, with every kind of contribution made to it.
    @Transactional(readOnly = true)
    public PageResponse<ContributedArticleResponse> getContributedArticles(String username, String language,
            Pageable pageable) {
        User user = requirePublicUser(username);
        List<ContributedArticleRow> rows = userRepository.findContributedArticles(user.getId(),
                pageable.getPageSize(), pageable.getOffset());
        List<Article> articles = articleRepository.findAllById(rows.stream().map(ContributedArticleRow::getArticleId).toList());
        Map<UUID, ArticleCardResponse> cards = articleCardService.cardsOf(articles, language);
        Map<UUID, Article> byId = articles.stream().collect(Collectors.toMap(Article::getId, Function.identity()));

        List<ContributedArticleResponse> items = rows.stream()
                .filter(row -> byId.containsKey(row.getArticleId()))
                .map(row -> new ContributedArticleResponse(cards.get(row.getArticleId()), parseTypes(row.getTypes())))
                .toList();
        return new PageResponse<>(items, userRepository.countContributedArticles(user.getId()));
    }

    // Unknown, disabled, still-registering (no username) and incomplete profiles are all the same
    // 404: none of them has a public profile.
    private User requirePublicUser(String username) {
        return userRepository.findByUsernameIgnoringCase(username)
                .filter(User::isEnabled)
                .filter(User::isProfileComplete)
                .orElseThrow(UserNotFoundException::new);
    }

    // An expert reviewer holds one row per expertise, hence distinct.
    private List<String> roleNames(UUID userId) {
        return userRoleRepository.findByUserId(userId).stream()
                .map(UserRole::getRole)
                .map(Role::name)
                .distinct()
                .toList();
    }

    private static List<ContributionType> parseTypes(String types) {
        return Arrays.stream(types.split(",")).map(ContributionType::valueOf).sorted().toList();
    }

    private static @Nullable String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
