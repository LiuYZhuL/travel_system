package com.travel.travel_system.service.impl;

import com.travel.travel_system.model.Anchor;
import com.travel.travel_system.model.Photo;
import com.travel.travel_system.model.Trip;
import com.travel.travel_system.model.enums.MatchMethod;
import com.travel.travel_system.model.enums.PrivacyMode;
import com.travel.travel_system.repository.AnchorRepository;
import com.travel.travel_system.repository.PhotoRepository;
import com.travel.travel_system.repository.TripRepository;
import com.travel.travel_system.service.PhotoService;
import com.travel.travel_system.service.pub.OssService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Optional;

@Service
public class PhotoServiceImpl implements PhotoService {

    @Autowired
    private PhotoRepository photoRepository;

    @Autowired
    private TripRepository tripRepository;

    @Autowired
    private AnchorRepository anchorRepository;

    @Autowired
    private OssService ossService;

    @Override
    @Transactional
    public Photo uploadPhoto(Long tripId, String fileName, String contentType, String fileBase64) {
        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> new RuntimeException("行程不存在，tripId: " + tripId));

        byte[] fileBytes = Base64.getDecoder().decode(fileBase64);
        String objectKey = ossService.generateFileName(fileName, "photos/" + tripId);
        String url = ossService.uploadFile(fileBytes, objectKey);

        Photo photo = new Photo();
        photo.setUserId(trip.getUserId());
        photo.setTripId(tripId);
        photo.setObjectKey(url);
        photo.setFileHash(calculateHash(fileBytes));
        photo.setShotTimeExif(new Date());
        photo.setCreatedAt(new Date());
        photo.setPrivacyMode(PrivacyMode.PUBLIC);
        photo.setIsCover(false);

        Photo savedPhoto = photoRepository.save(photo);

        Anchor anchor = new Anchor();
        anchor.setUserId(trip.getUserId());
        anchor.setTripId(tripId);
        anchor.setPhotoId(savedPhoto.getId());
        anchor.setMatchedTs(System.currentTimeMillis());
        anchor.setMatchMethod(MatchMethod.EXIF_DIRECT);
        anchor.setConfidence(1.0f);
        anchor.setManualOverride(false);
        anchor.setCreatedAt(new Date());
        anchorRepository.save(anchor);

        return savedPhoto;
    }

    @Override
    public Photo getPhotoAnchor(Long photoId) {
        return photoRepository.findById(photoId).orElse(null);
    }

    @Override
    public List<Photo> getPhotosByTrip(Long tripId) {
        return photoRepository.findByTripIdOrderByShotTimeExifAsc(tripId);
    }

    @Override
    @Transactional
    public Photo updatePhotoInfo(Long photoId, String userCaption, String privacyMode) {
        Photo photo = photoRepository.findById(photoId)
                .orElseThrow(() -> new RuntimeException("照片不存在，photoId: " + photoId));

        if (userCaption != null) {
            photo.setUserCaption(userCaption);
        }
        if (privacyMode != null && !privacyMode.trim().isEmpty()) {
            try {
                photo.setPrivacyMode(PrivacyMode.valueOf(privacyMode.toUpperCase()));
            } catch (IllegalArgumentException ignored) {
            }
        }

        return photoRepository.save(photo);
    }

    @Override
    @Transactional
    public void deletePhoto(Long photoId) {
        Photo photo = photoRepository.findById(photoId)
                .orElseThrow(() -> new RuntimeException("照片不存在，photoId: " + photoId));

        List<Anchor> anchors = anchorRepository.findByPhotoId(photoId);
        for (Anchor anchor : anchors) {
            anchorRepository.delete(anchor);
        }

        if (photo.getObjectKey() != null) {
            try {
                String objectName = extractObjectName(photo.getObjectKey());
                ossService.deleteFile(objectName);
            } catch (Exception ignored) {
            }
        }

        photoRepository.delete(photo);
    }

    private String calculateHash(byte[] bytes) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] hashBytes = md.digest(bytes);
            StringBuilder sb = new StringBuilder();
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private String extractObjectName(String url) {
        if (url == null) return null;
        int idx = url.indexOf(".com/");
        if (idx > 0) {
            return url.substring(idx + 5);
        }
        return url;
    }
}
