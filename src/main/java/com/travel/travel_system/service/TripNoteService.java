package com.travel.travel_system.service;

import com.travel.travel_system.model.TripNote;

import java.util.List;
import java.util.Optional;

public interface TripNoteService {

    TripNote createNote(TripNote note);

    Optional<TripNote> getNote(Long noteId);

    List<TripNote> getNotesByTrip(Long tripId);

    List<TripNote> getNotesByUser(Long userId);

    List<TripNote> searchNotesByTitle(Long tripId, String keyword);

    List<TripNote> getNotesWithAnchor(Long tripId);

    List<TripNote> getNotesWithLocation(Long tripId);

    TripNote updateNote(Long noteId, String title, String content, String privacyMode);

    TripNote updateAnchor(Long noteId, Long anchorTs, byte[] latEnc, byte[] lngEnc);

    TripNote updateLocation(Long noteId, Double lat, Double lng, String locationName, String coordType);

    TripNote applyDefaultAnchorAndLocation(Long noteId, Long anchorTs, Double lat, Double lng, String locationName, String coordType, String coordinateSource);

    void deleteNote(Long noteId);

    void deleteNotesByTrip(Long tripId);

    long countByTrip(Long tripId);
}
