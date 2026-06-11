package com.BossAi.bossAi.exceptions;

import com.BossAi.bossAi.service.edl.EdlValidator;
import lombok.Getter;

/**
 * EDL rejected by strict validation (editor PUT /timeline). Carries the
 * structured issues so the API can return per-field errors the timeline UI
 * pins to the offending segment, instead of one concatenated string.
 */
@Getter
public class EdlValidationException extends RuntimeException {

    private final transient EdlValidator.ValidationResult result;

    public EdlValidationException(EdlValidator.ValidationResult result) {
        super("EDL validation failed: " + String.join("; ", result.errors()));
        this.result = result;
    }
}
