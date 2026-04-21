package com.travel.travel_system.service.impl;

import com.travel.travel_system.model.Anchor;
import com.travel.travel_system.model.Photo;
import com.travel.travel_system.model.Trip;
import com.travel.travel_system.model.enums.PrivacyMode;
import com.travel.travel_system.repository.AnchorRepository;
import com.travel.travel_system.repository.PhotoRepository;
import com.travel.travel_system.repository.TripRepository;
import com.travel.travel_system.repository.VideoRepository;
import com.travel.travel_system.service.MediaAnchorProjectionService;
import com.travel.travel_system.service.PhotoService;
import com.travel.travel_system.service.pub.OssService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.drew.imaging.ImageMetadataReader;
import com.drew.imaging.ImageProcessingException;
import com.drew.lang.GeoLocation;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import com.drew.metadata.exif.GpsDirectory;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import java.util.Base64;
import java.util.Date;
import java.util.List;

@Service
public class PhotoServiceImpl implements PhotoService {

    @Autowired
    private PhotoRepository photoRepository;
    @Autowired
    private TripRepository tripRepository;
    @Autowired
    private VideoRepository videoRepository;
    @Autowired
    private AnchorRepository anchorRepository;
    @Autowired
    private OssService ossService;
    @Autowired
    private MediaAnchorProjectionService mediaAnchorProjectionService;

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
        photo.setCreatedAt(new Date());
        photo.setPrivacyMode(PrivacyMode.PUBLIC);
        photo.setIsCover(false);
        photo.setBindingStatus("PENDING");

        PhotoExifMeta exif = readPhotoExif(fileBytes);
        if (exif != null) {
            photo.setShotTimeExif(exif.shotTime);
            if (exif.lat != null && exif.lng != null) {
                photo.setLatEnc(TrackPointServiceImpl.encodeDoubleStatic(exif.lat));
                photo.setLngEnc(TrackPointServiceImpl.encodeDoubleStatic(exif.lng));
                photo.setCaptureCoordSource("EXIF");
                photo.setCaptureCoordType("WGS84");
            } else {
                photo.setCaptureCoordSource("NONE");
                photo.setCaptureCoordType(null);
            }
            photo.setCaptureTimeSource(exif.shotTime != null ? "EXIF" : "NONE");
        } else {
            // P0 修复：不要再用上传时间冒充拍摄时间
            photo.setShotTimeExif(null);
            photo.setCaptureTimeSource("NONE");
            photo.setCaptureCoordSource("NONE");
            photo.setCaptureCoordType(null);
        }

        Photo savedPhoto = photoRepository.save(photo);
        refreshTripMediaCounts(tripId);

        // 统一走投影服务，不再在这里手工创建占位 Anchor
        mediaAnchorProjectionService.projectPhotoAnchor(savedPhoto.getId(), tripId);

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
    public Photo updatePhotoInfo(Long photoId, String userCaption, String privacyMode, Boolean isCover) {
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

        if (isCover != null) {
            if (Boolean.TRUE.equals(isCover)) {
                for (Photo other : photoRepository.findByTripIdAndIsCoverTrue(photo.getTripId())) {
                    if (!other.getId().equals(photo.getId())) {
                        other.setIsCover(false);
                        photoRepository.save(other);
                    }
                }
            }
            photo.setIsCover(isCover);
        }

        Photo saved = photoRepository.save(photo);

        // 如果你后面扩展了“手动填写时间/手动打点”，在保存 override 字段后
        // 再调用一次：
        // mediaAnchorProjectionService.projectPhotoAnchor(saved.getId(), saved.getTripId());

        return saved;
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
        refreshTripMediaCounts(photo.getTripId());
    }

    @Override
    @Transactional
    public Photo updatePhotoAssistInfo(Long photoId, Long captureTsOverride, Double manualLat, Double manualLng, String coordType, String locationName, String locationMode) {
        Photo photo = photoRepository.findById(photoId)
                .orElseThrow(() -> new RuntimeException("照片不存在，photoId: " + photoId));

        photo.setCaptureTsOverride(captureTsOverride);

        String normalizedMode = locationMode == null ? "" : locationMode.trim().toUpperCase();
        if (manualLat != null && manualLng != null && !"EXIF".equals(normalizedMode)) {
            photo.setCaptureLatOverride(TrackPointServiceImpl.encodeDoubleStatic(manualLat));
            photo.setCaptureLngOverride(TrackPointServiceImpl.encodeDoubleStatic(manualLng));
            photo.setCaptureCoordSource("MANUAL");
            photo.setCaptureCoordType(coordType != null && !coordType.isBlank() ? coordType.toUpperCase() : "GCJ02");
        } else if ("NONE".equals(normalizedMode)) {
            photo.setCaptureLatOverride(null);
            photo.setCaptureLngOverride(null);
            photo.setCaptureCoordSource("NONE");
            photo.setCaptureCoordType(null);
        } else if ("EXIF".equals(normalizedMode)) {
            photo.setCaptureLatOverride(null);
            photo.setCaptureLngOverride(null);
            if (photo.getLatEnc() != null && photo.getLngEnc() != null) {
                photo.setCaptureCoordSource("EXIF");
                photo.setCaptureCoordType("WGS84");
            } else {
                photo.setCaptureCoordSource("NONE");
                photo.setCaptureCoordType(null);
            }
        } else {
            photo.setCaptureLatOverride(null);
            photo.setCaptureLngOverride(null);
            if (captureTsOverride != null) {
                photo.setCaptureCoordSource("NONE");
                photo.setCaptureCoordType(null);
            }
        }

        if (captureTsOverride != null) {
            photo.setCaptureTimeSource("USER_INPUT");
        }
        if (locationName != null) {
            photo.setLocationName(locationName.isBlank() ? null : locationName.trim());
        }

        Photo saved = photoRepository.save(photo);

        // 用户补时间 / 手动打点后，立即重投影
        mediaAnchorProjectionService.projectPhotoAnchor(saved.getId(), saved.getTripId());
        return saved;
    }

    private PhotoExifMeta readPhotoExif(byte[] fileBytes) {
        if (fileBytes == null || fileBytes.length == 0) {
            return null;
        }

        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(fileBytes)) {
            Metadata metadata = ImageMetadataReader.readMetadata(inputStream);

            PhotoExifMeta meta = new PhotoExifMeta();

            ExifSubIFDDirectory subIfd = metadata.getFirstDirectoryOfType(ExifSubIFDDirectory.class);
            ExifIFD0Directory ifd0 = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
            GpsDirectory gpsDirectory = metadata.getFirstDirectoryOfType(GpsDirectory.class);

            // 1. 时间：优先 DateTimeOriginal，其次 DateDigitized，再其次 IFD0 DateTime
            if (subIfd != null) {
                Date original = subIfd.getDateOriginal();
                if (original != null) {
                    meta.shotTime = original;
                } else {
                    Date digitized = subIfd.getDateDigitized();
                    if (digitized != null) {
                        meta.shotTime = digitized;
                    }
                }
            }
            if (meta.shotTime == null && ifd0 != null) {
                try {
                    Date dateTime = ifd0.getDate(ExifIFD0Directory.TAG_DATETIME);
                    if (dateTime != null) {
                        meta.shotTime = dateTime;
                    }
                } catch (Exception ignored) {
                }
            }

            // 2. GPS：Exif GPS 原始坐标通常是 WGS84
            if (gpsDirectory != null) {
                GeoLocation geoLocation = gpsDirectory.getGeoLocation();
                if (geoLocation != null && !geoLocation.isZero()) {
                    meta.lat = geoLocation.getLatitude();
                    meta.lng = geoLocation.getLongitude();
                }
            }

            // 两者都没有就返回 null
            if (meta.shotTime == null && meta.lat == null && meta.lng == null) {
                return null;
            }
            return meta;
        } catch (ImageProcessingException | IOException e) {
            return null;
        }
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

    private void refreshTripMediaCounts(Long tripId) {
        if (tripId == null) {
            return;
        }
        tripRepository.findById(tripId).ifPresent(trip -> {
            trip.setPhotoCount((int) photoRepository.countByTripId(tripId));
            trip.setVideoCount((int) videoRepository.countByTripId(tripId));
            tripRepository.save(trip);
        });
    }

    private static class PhotoExifMeta {
        Date shotTime;
        Double lat;
        Double lng;
    }
}
