package com.travel.travel_system.service;

import com.travel.travel_system.model.Anchor;

import java.util.List;
import java.util.Optional;

public interface AnchorService {

    Anchor createAnchor(Anchor anchor);

    Optional<Anchor> getAnchor(Long anchorId);

    List<Anchor> getAnchorsByTrip(Long tripId);

    List<Anchor> getAnchorsByPhoto(Long photoId);

    List<Anchor> getAnchorsByVideo(Long videoId);

    Anchor updateAnchor(Long anchorId, Long matchedTs, byte[] latEnc, byte[] lngEnc, Float confidence);

    Anchor manualOverride(Long anchorId, Long matchedTs, byte[] latEnc, byte[] lngEnc);

    void deleteAnchor(Long anchorId);

    void deleteAnchorsByTrip(Long tripId);

    long countByTrip(Long tripId);

    List<Anchor> getHighConfidenceAnchors(Long tripId, Float minConfidence);

    List<Anchor> getManualOverrideAnchors(Long tripId);
}
