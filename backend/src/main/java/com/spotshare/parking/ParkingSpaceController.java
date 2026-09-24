package com.spotshare.parking;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.spotshare.auth.AuthenticatedUser;
import com.spotshare.parking.ParkingSpaceService.PhotoContent;
import com.spotshare.parking.dto.CreateSpaceRequest;
import com.spotshare.parking.dto.PhotoDto;
import com.spotshare.parking.dto.SpaceDetailDto;
import com.spotshare.parking.dto.UpdateSpaceRequest;

import jakarta.validation.Valid;

/**
 * Host parking-space endpoints. Every space-scoped route requires the caller
 * to be the space's host (403 otherwise); the DTOs returned are the owner
 * view and include private fields. The photo-content endpoint is the one
 * public route (photos carry no private location data).
 */
@RestController
@RequestMapping("/api/v1/spaces")
public class ParkingSpaceController {

    private final ParkingSpaceService spaces;

    public ParkingSpaceController(ParkingSpaceService spaces) {
        this.spaces = spaces;
    }

    @PostMapping
    public ResponseEntity<SpaceDetailDto> createSpace(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody CreateSpaceRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(spaces.createSpace(principal.id(), req));
    }

    @GetMapping("/mine")
    public List<SpaceDetailDto> mySpaces(@AuthenticationPrincipal AuthenticatedUser principal) {
        return spaces.mySpaces(principal.id());
    }

    @GetMapping("/{id}")
    public SpaceDetailDto getSpace(@AuthenticationPrincipal AuthenticatedUser principal,
                                   @PathVariable("id") UUID id) {
        return spaces.getSpace(principal.id(), id);
    }

    @PutMapping("/{id}")
    public SpaceDetailDto updateSpace(@AuthenticationPrincipal AuthenticatedUser principal,
                                      @PathVariable("id") UUID id,
                                      @Valid @RequestBody UpdateSpaceRequest req) {
        return spaces.updateSpace(principal.id(), id, req);
    }

    /**
     * Soft delete: deactivates the space (active=false). The record and its
     * photos are retained. Idempotent — deactivating twice is a no-op success.
     */
    @DeleteMapping("/{id}")
    public SpaceDetailDto deactivateSpace(@AuthenticationPrincipal AuthenticatedUser principal,
                                          @PathVariable("id") UUID id) {
        return spaces.deactivateSpace(principal.id(), id);
    }

    @PostMapping(value = "/{id}/photos", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<PhotoDto> addPhoto(@AuthenticationPrincipal AuthenticatedUser principal,
                                            @PathVariable("id") UUID id,
                                            @RequestParam("photo") MultipartFile photo) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(spaces.addPhoto(principal.id(), id, photo));
    }

    @DeleteMapping("/{id}/photos/{photoId}")
    public ResponseEntity<Void> deletePhoto(@AuthenticationPrincipal AuthenticatedUser principal,
                                            @PathVariable("id") UUID id,
                                            @PathVariable("photoId") UUID photoId) {
        spaces.deletePhoto(principal.id(), id, photoId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Serves the photo bytes. Public (no auth): photos contain no private
     * location data, and discovery shows them publicly in Phase 4.
     */
    @GetMapping("/{id}/photos/{photoId}/content")
    public ResponseEntity<byte[]> photoContent(@PathVariable("id") UUID id,
                                               @PathVariable("photoId") UUID photoId) {
        PhotoContent content = spaces.photoContent(id, photoId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, content.contentType())
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=31536000, immutable")
                .body(content.data());
    }
}
