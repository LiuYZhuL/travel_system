package com.travel.travel_system.utils;

import com.travel.travel_system.model.dto.RoadNetwork;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class RoadNetworkSerializer {

    private static final String CACHE_DIR = "road_network_cache";

    public static boolean cacheExists(double lat, double lon, double radiusKm) {
        return Files.exists(buildCachePath(lat, lon, radiusKm));
    }

    public static RoadNetwork loadFromCache(double lat, double lon, double radiusKm) {
        Path path = buildCachePath(lat, lon, radiusKm);
        if (!Files.exists(path)) {
            return null;
        }
        try (ObjectInputStream ois = new ObjectInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
            Object obj = ois.readObject();
            if (!(obj instanceof RoadNetwork roadNetwork)) {
                System.err.println("缓存文件不是 RoadNetwork: " + path);
                return null;
            }
            // 反序列化后重建邻接表，确保反向边和运行期缓存恢复一致。
            roadNetwork.rebuildAdjacencyList();
            System.out.println("从本地缓存加载路网数据: " + path + " (" + readableSize(path) + ")");
            System.out.println("after cache load: nodes=" + roadNetwork.getNodeCount() + ", edges=" + roadNetwork.getEdgeCount());
            return roadNetwork;
        } catch (Exception e) {
            System.err.println("加载路网缓存失败，删除损坏缓存: " + path + ", error=" + e.getMessage());
            try {
                Files.deleteIfExists(path);
            } catch (IOException ignore) {
            }
            return null;
        }
    }

    public static void saveToCache(RoadNetwork roadNetwork, double lat, double lon, double radiusKm) {
        if (roadNetwork == null) {
            return;
        }
        try {
            Files.createDirectories(Paths.get(CACHE_DIR));
            Path path = buildCachePath(lat, lon, radiusKm);
            System.out.println("before cache save: nodes=" + roadNetwork.getNodeCount() + ", edges=" + roadNetwork.getEdgeCount());
            try (ObjectOutputStream oos = new ObjectOutputStream(new BufferedOutputStream(Files.newOutputStream(path)))) {
                oos.writeObject(roadNetwork);
                oos.flush();
            }
            System.out.println("路网数据已缓存到: " + path + " (" + readableSize(path) + ")");
        } catch (Exception e) {
            throw new RuntimeException("保存路网缓存失败", e);
        }
    }

    public static void clearAllCache() {
        Path dir = Paths.get(CACHE_DIR);
        if (!Files.exists(dir)) {
            return;
        }
        try {
            Files.list(dir)
                    .filter(p -> p.getFileName().toString().endsWith(".rnc"))
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignore) {
                        }
                    });
        } catch (IOException ignore) {
        }
    }

    private static Path buildCachePath(double lat, double lon, double radiusKm) {
        long qLat = Math.round(lat * 100);
        long qLon = Math.round(lon * 100);
        long qRadius = Math.max(1, Math.round(radiusKm));
        return Paths.get(CACHE_DIR, "rn_" + qLat + "_" + qLon + "_" + qRadius + ".rnc");
    }

    private static String readableSize(Path path) {
        try {
            long bytes = Files.size(path);
            if (bytes < 1024) {
                return bytes + " B";
            }
            if (bytes < 1024 * 1024) {
                return (bytes / 1024) + " KB";
            }
            return String.format("%.2f MB", bytes / 1024.0 / 1024.0);
        } catch (IOException e) {
            return "unknown";
        }
    }
}
