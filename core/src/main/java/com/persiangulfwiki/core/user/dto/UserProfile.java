package com.persiangulfwiki.core.user.dto;

import com.persiangulfwiki.core.user.entity.User;

import java.util.List;

public record UserProfile(User user, List<String> roles) {
}
