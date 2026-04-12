package com.travel.travel_system.service.impl;

import com.travel.travel_system.model.TripNote;
import com.travel.travel_system.repository.TripNoteRepository;
import com.travel.travel_system.service.TripNoteService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;
import java.util.Optional;

@Service
public class TripNoteServiceImpl implements TripNoteService {

    @Autowired
    private TripNoteRepository tripNoteRepository;

    @Override
    @Transactional
    public TripNote createNote(TripNote note) {
        if (note.getCreatedAt() == null) {
            note.setCreatedAt(new Date());
        }
        if (note.getPrivacyMode() == null) {
            note.setPrivacyMode("PUBLIC");
        }
        return tripNoteRepository.save(note);
    }

    @Override
    public Optional<TripNote> getNote(Long noteId) {
        return tripNoteRepository.findById(noteId);
    }

    @Override
    public List<TripNote> getNotesByTrip(Long tripId) {
        return tripNoteRepository.findByTripIdOrderByCreatedAtDesc(tripId);
    }

    @Override
    public List<TripNote> getNotesByUser(Long userId) {
        return tripNoteRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    @Override
    public List<TripNote> searchNotesByTitle(Long tripId, String keyword) {
        return tripNoteRepository.findByTripIdAndTitleContaining(tripId, keyword);
    }

    @Override
    public List<TripNote> getNotesWithAnchor(Long tripId) {
        return tripNoteRepository.findByTripIdAndAnchorTsIsNotNull(tripId);
    }

    @Override
    public List<TripNote> getNotesWithLocation(Long tripId) {
        return tripNoteRepository.findByTripIdAndLatEncIsNotNull(tripId);
    }

    @Override
    @Transactional
    public TripNote updateNote(Long noteId, String title, String content, String privacyMode) {
        TripNote note = tripNoteRepository.findById(noteId)
                .orElseThrow(() -> new RuntimeException("笔记不存在，noteId: " + noteId));

        if (title != null) {
            note.setTitle(title);
        }
        if (content != null) {
            note.setContent(content);
        }
        if (privacyMode != null && !privacyMode.trim().isEmpty()) {
            note.setPrivacyMode(privacyMode);
        }
        note.setUpdatedAt(new Date());

        return tripNoteRepository.save(note);
    }

    @Override
    @Transactional
    public TripNote updateAnchor(Long noteId, Long anchorTs, byte[] latEnc, byte[] lngEnc) {
        TripNote note = tripNoteRepository.findById(noteId)
                .orElseThrow(() -> new RuntimeException("笔记不存在，noteId: " + noteId));

        if (anchorTs != null) {
            note.setAnchorTs(anchorTs);
        }
        if (latEnc != null) {
            note.setLatEnc(latEnc);
            note.setCoordinateSource("MANUAL");
        }
        if (lngEnc != null) {
            note.setLngEnc(lngEnc);
        }
        note.setUpdatedAt(new Date());

        return tripNoteRepository.save(note);
    }

    @Override
    @Transactional
    public TripNote updateLocation(Long noteId, Double lat, Double lng, String locationName, String coordType) {
        TripNote note = tripNoteRepository.findById(noteId)
                .orElseThrow(() -> new RuntimeException("笔记不存在，noteId: " + noteId));

        if (lat != null && lng != null) {
            note.setLatEnc(encodeDouble(lat));
            note.setLngEnc(encodeDouble(lng));
            note.setCoordinateSource("MANUAL");
            note.setCoordType(coordType != null ? coordType : "GCJ02");
        }
        if (locationName != null) {
            note.setLocationName(locationName);
        }
        note.setUpdatedAt(new Date());

        return tripNoteRepository.save(note);
    }

    @Override
    @Transactional
    public void deleteNote(Long noteId) {
        tripNoteRepository.deleteById(noteId);
    }

    @Override
    @Transactional
    public void deleteNotesByTrip(Long tripId) {
        tripNoteRepository.deleteByTripId(tripId);
    }

    @Override
    public long countByTrip(Long tripId) {
        return tripNoteRepository.countByTripId(tripId);
    }

    private byte[] encodeDouble(double value) {
        long bits = Double.doubleToLongBits(value);
        byte[] bytes = new byte[8];
        for (int i = 0; i < 8; i++) {
            bytes[i] = (byte) ((bits >> (i * 8)) & 0xFF);
        }
        return bytes;
    }
}
