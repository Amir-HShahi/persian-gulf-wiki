package com.persiangulfwiki.core.user.repository;

import java.util.UUID;

public interface UserContributionCountRow {

    UUID getUserId();

    long getContributionCount();
}
