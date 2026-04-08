package com.travel.travel_system.service.impl;

import com.travel.travel_system.model.Anchor;
import com.travel.travel_system.repository.AnchorRepository;
import com.travel.travel_system.service.AnchorService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;
import java.util.Optional;

@Service
public class AnchorServiceImpl implements AnchorService {

    @Autowired
    private AnchorRepository anchorRepository;

    @Override
    @Transactional
    public Anchor createAnchor(Anchor anchor) {
        if (anchor.getCreatedAt() == null) {
            anchor.setCreatedAt(new Date());
        }
        return anchorRepository.save(anchor);
    }

    @Override
    public Optional<Anchor> getAnchor(Long anchorId) {
        return anchorRepository.findById(anchorId);
    }

    @Override
    public List<Anchor> getAnchorsByTrip(Long tripId) {
        return anchorRepository.findByTripIdOrderByMatchedTsAsc(tripId);
    }

    @Override
    public List<Anchor> getAnchorsByPhoto(Long photoId) {
        return anchorRepository.findByPhotoId(photoId);
    }

    @Override
    public List<Anchor> getAnchorsByVideo(Long videoId) {
        return anchorRepository.findByVideoId(videoId);
    }

    @Override
    @Transactional
    public Anchor updateAnchor(Long anchorId, Long matchedTs, byte[] latEnc, byte[] lngEnc, Float confidence) {
        Anchor anchor = anchorRepository.findById(anchorId)
                .orElseThrow(() -> new RuntimeException("锚点不存在，anchorId: " + anchorId));

        if (matchedTs != null) {
            anchor.setMatchedTs(matchedTs);
        }
        if (latEnc != null) {
            anchor.setLatEnc(latEnc);
        }
        if (lngEnc != null) {
            anchor.setLngEnc(lngEnc);
        }
        if (confidence != null) {
            anchor.setConfidence(confidence);
        }
        anchor.setUpdatedAt(new Date());

        return anchorRepository.save(anchor);
    }

    @Override
    @Transactional
    public Anchor manualOverride(Long anchorId, Long matchedTs, byte[] latEnc, byte[] lngEnc) {
        Anchor anchor = anchorRepository.findById(anchorId)
                .orElseThrow(() -> new RuntimeException("锚点不存在，anchorId: " + anchorId));

        if (matchedTs != null) {
            anchor.setMatchedTs(matchedTs);
        }
        if (latEnc != null) {
            anchor.setLatEnc(latEnc);
        }
        if (lngEnc != null) {
            anchor.setLngEnc(lngEnc);
        }
        anchor.setManualOverride(true);
        anchor.setConfidence(1.0f);
        anchor.setUpdatedAt(new Date());

        return anchorRepository.save(anchor);
    }

    @Override
    @Transactional
    public void deleteAnchor(Long anchorId) {
        anchorRepository.deleteById(anchorId);
    }

    @Override
    @Transactional
    public void deleteAnchorsByTrip(Long tripId) {
        anchorRepository.deleteByTripId(tripId);
    }

    @Override
    public long countByTrip(Long tripId) {
        return anchorRepository.countByTripId(tripId);
    }

    @Override
    public List<Anchor> getHighConfidenceAnchors(Long tripId, Float minConfidence) {
        return anchorRepository.findByTripIdAndConfidenceGreaterThanEqual(tripId, minConfidence);
    }

    @Override
    public List<Anchor> getManualOverrideAnchors(Long tripId) {
        return anchorRepository.findByTripIdAndManualOverrideTrue(tripId);
    }
}
