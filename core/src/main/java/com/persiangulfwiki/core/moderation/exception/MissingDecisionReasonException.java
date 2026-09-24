package com.persiangulfwiki.core.moderation.exception;

import com.persiangulfwiki.core.common.exception.BadRequestException;

// 400, and deliberately not a bean-validation constraint on DecideRequest.reason. Whether a
// reason is required depends on which decision was chosen -- required for REJECT and
// REQUEST_CHANGES, meaningless for APPROVE -- and a field-level annotation cannot express a
// rule that reads another field. ModerationService owns it instead; see
// requireReasonWhenSendingBack there.
public class MissingDecisionReasonException extends BadRequestException {

    private static final String DEFAULT_MESSAGE = "a reason is required for this decision";

    public MissingDecisionReasonException() {
        super(DEFAULT_MESSAGE);
    }

    public MissingDecisionReasonException(String message) {
        super(message);
    }
}
