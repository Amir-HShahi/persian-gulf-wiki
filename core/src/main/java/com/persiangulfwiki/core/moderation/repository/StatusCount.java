package com.persiangulfwiki.core.moderation.repository;

// One row of a native GROUP BY over article_revisions.status; status is the RevisionStatus name.
public interface StatusCount {

    String getStatus();

    long getCount();
}
