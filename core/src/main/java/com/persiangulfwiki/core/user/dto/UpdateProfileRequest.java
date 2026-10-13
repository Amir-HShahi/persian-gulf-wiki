package com.persiangulfwiki.core.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

import java.util.List;

// A full replacement of the editable profile: an omitted bio, affiliation or links clears it.
public record UpdateProfileRequest(
        @NotBlank(message = "{validation.fullName.required}")
        @Size(max = 100, message = "{validation.fullName.tooLong}")
        String fullName,

        @Size(max = 500, message = "{validation.bio.tooLong}")
        @Schema(nullable = true)
        @Nullable String bio,

        @Size(max = 150, message = "{validation.affiliation.tooLong}")
        @Schema(description = "Organization and/or title, e.g. \"University of Tehran, Marine Biology\".", nullable = true)
        @Nullable String affiliation,

        // https only: the frontend renders these as links, and any other scheme (javascript:,
        // data:) would make a profile a script-injection vector.
        @Size(max = 5, message = "{validation.links.tooMany}")
        @Schema(description = "Up to 5 https URLs, shown in this order.", nullable = true)
        @Nullable List<
                @NotBlank(message = "{validation.link.invalid}")
                @Size(max = 500, message = "{validation.link.tooLong}")
                @Pattern(regexp = "^https://[^\\s/?#@]+\\.[^\\s/?#@]+([/?#]\\S*)?$", message = "{validation.link.invalid}")
                String> links) {
}
