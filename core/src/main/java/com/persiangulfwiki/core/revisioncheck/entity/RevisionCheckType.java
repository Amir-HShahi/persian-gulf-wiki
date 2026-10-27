package com.persiangulfwiki.core.revisioncheck.entity;

import com.persiangulfwiki.core.jobqueue.entity.JobType;

import io.swagger.v3.oas.annotations.media.Schema;

// The worker's two advisory checks on a submitted article revision, each one row of
// revision_checks (V38) and one job per submit.
@Schema(description = RevisionCheckType.VALUES_DOC)
public enum RevisionCheckType {
    NORMALIZE(JobType.NORMALIZE),
    LINKS(JobType.LINKS);

    // One line per value; shown wherever this type appears in the API docs.
    public static final String VALUES_DOC = """
            - `NORMALIZE`: suggested text fixes, such as an Arabic ي in a Persian article or a detached prefix.
            - `LINKS`: whether each external link in the body still works.""";

    private final JobType jobType;

    RevisionCheckType(JobType jobType) {
        this.jobType = jobType;
    }

    public JobType jobType() {
        return jobType;
    }
}
