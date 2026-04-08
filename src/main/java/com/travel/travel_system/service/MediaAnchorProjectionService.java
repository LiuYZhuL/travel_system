package com.travel.travel_system.service;

import com.travel.travel_system.model.Anchor;

public interface MediaAnchorProjectionService {

    Anchor projectPhotoAnchor(Long photoId, Long tripId);

    Anchor projectVideoAnchor(Long videoId, Long tripId);

    void refreshTripMediaAnchors(Long tripId);

    void markTripRouteDirtyIfNeeded(Long tripId);
}