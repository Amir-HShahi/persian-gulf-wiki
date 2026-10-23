package com.persiangulfwiki.core.expertreviewer.entity;

public enum ApplicationStatus {
    PENDING, APPROVED, REJECTED;

    // One line per value; shown wherever this type appears in the API docs.
    public static final String VALUES_DOC = """
            - `PENDING`: waiting for an admin's decision.
            - `APPROVED`: accepted; the applicant is now an expert reviewer for that kind.
            - `REJECTED`: declined.""";
}
