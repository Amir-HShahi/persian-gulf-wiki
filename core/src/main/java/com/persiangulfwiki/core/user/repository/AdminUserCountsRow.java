package com.persiangulfwiki.core.user.repository;

public interface AdminUserCountsRow {

    long getTotal();

    long getBlocked();

    long getAdmin();

    long getModerator();

    long getExpertReviewer();

    long getContributor();
}
