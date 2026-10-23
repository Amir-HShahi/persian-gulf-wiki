package com.persiangulfwiki.core.dev;

import com.persiangulfwiki.core.dev.dto.DevDemoContentStatusResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import lombok.RequiredArgsConstructor;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

// The demo dataset for frontend work: one call fills every page the designs show, one call empties
// it again. Separate from the DevTest* fixtures, which mint one minimal row per E2E test; see
// DevDemoContent for why the two never share a marker.
//
// Gated like every other fixture route: @Profile("dev") here, so the bean does not exist anywhere
// else, and DevSecurityConfig's profiled chain for /api/dev/**. No input of any kind is accepted --
// the dataset is the one shipped in the image -- so nothing a caller sends reaches a query, a
// storage key or a file path.
@RestController
@RequestMapping("/api/dev/demo-content")
@Profile("dev")
@RequiredArgsConstructor
@Tag(name = "Dev Demo Content", description = "Dev-profile-only demo dataset for frontend work. Not registered in any "
        + "other profile.")
public class DevDemoContentController {

    private final DevDemoContentSeeder seeder;

    @Operation(summary = "Start filling the wiki with the demo dataset", description = "Starts the fill and answers "
            + "at once; follow it with the status route below, which reports each step and, at the end, what was "
            + "created or why it failed. A fill removes any demo content already there, then creates the whole "
            + "dataset again: about 50 published articles in Persian, English and Arabic across every topic, the "
            + "subjects behind them with complete infoboxes, sources, image galleries with every size variant stored, "
            + "360° panoramas, revision histories by several contributors, expert verifications, a few revisions "
            + "awaiting review and a featured article. The result is the same every time. Demo accounts "
            + "(demo-…@dev.local) share the documented dev password. Anything not created here is left alone; "
            + "content created by hand on a demo article is removed with that article on the next fill. A fill takes "
            + "about fifteen seconds.")
    @ApiResponse(responseCode = "202", description = "The fill has started. The body is its status, RUNNING.")
    @ApiResponse(responseCode = "409", description = "A fill or removal is already running. Wait for it to finish "
            + "(see the status route) and try again.")
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    // Bare DTO rather than the usual envelope, matching the other fixture controllers.
    public DevDemoContentStatusResponse start() {
        return seeder.start();
    }

    @Operation(summary = "Follow the demo dataset fill", description = "Where the latest fill stands: IDLE before the "
            + "first fill and after a removal, RUNNING with the current step and how far it got, SUCCEEDED with "
            + "what was created, or FAILED with the reason. Poll it every second or two while a fill runs.")
    @ApiResponse(responseCode = "200", description = "The latest fill's status.")
    @GetMapping
    @ResponseStatus(HttpStatus.OK)
    public DevDemoContentStatusResponse status() {
        return seeder.status();
    }

    @Operation(summary = "Remove the demo dataset", description = "Deletes everything the fill endpoint created, "
            + "including its stored images. A demo subject or account that content made by hand still depends on is "
            + "kept. Calling it when no demo content exists does nothing.")
    @ApiResponse(responseCode = "204", description = "The demo content is gone.")
    @ApiResponse(responseCode = "409", description = "A fill or removal is still running. Try again once it has finished.")
    @ApiResponse(responseCode = "500", description = "The database or image storage failed part-way. Calling again "
            + "finishes the removal.")
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void wipe() {
        seeder.wipe();
    }
}
