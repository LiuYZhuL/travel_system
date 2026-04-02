package com.travel.travel_system.utils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class GeolifeParser {
    public static List<Map<String, Object>> parseGeolifeFile(String filePath) throws IOException, ParseException {
        List<Map<String, Object>> trackPoints = new ArrayList<>();
        File file = new File(filePath);

        if (!file.exists()) {
            throw new IOException("File not found: " + filePath);
        }

        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            int lineCount = 0;

            while ((line = reader.readLine()) != null) {
                lineCount++;

                if (lineCount <= 6) {
                    continue;
                }

                String[] parts = line.split(",");
                if (parts.length >= 7) {
                    try {
                        double latitude = Double.parseDouble(parts[0]);
                        double longitude = Double.parseDouble(parts[1]);
                        String dateStr = parts[5] + " " + parts[6];
                        Date time = dateFormat.parse(dateStr);

                        Map<String, Object> point = new HashMap<>();
                        point.put("lat", latitude);
                        point.put("lng", longitude);
                        point.put("ts", time.getTime());
                        point.put("heading", 0.0);
                        point.put("speed", 1.2);
                        trackPoints.add(point);
                    } catch (NumberFormatException | ParseException e) {
                        e.printStackTrace();
                    }
                }
            }
        }

        return trackPoints;
    }
    public static List<Map<String, Object>> parseWuhanEVCSVFile(String filePath) throws IOException, ParseException {
        List<Map<String, Object>> trackPoints = new ArrayList<>();
        File file = new File(filePath);

        if (!file.exists()) {
            throw new IOException("File not found: " + filePath);
        }

        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            int lineCount = 0;

            while ((line = reader.readLine()) != null) {
                lineCount++;

                // 跳过表头（第 1 行）
                if (lineCount == 1) {
                    continue;
                }

                String[] parts = line.split(",");
                if (parts.length >= 12) {
                    try {
                        // CSV 格式：vehicle_id,id,ts,time_beijing,lat,lng,speed_mps,heading_deg,accuracy_m,running_status,charge_status,odometer_m
                        double latitude = Double.parseDouble(parts[4]);  // lat
                        double longitude = Double.parseDouble(parts[5]); // lng
                        long timestamp = Long.parseLong(parts[2]);       // ts (毫秒时间戳)
                        double speed = Double.parseDouble(parts[6]);     // speed_mps
                        double heading = Double.parseDouble(parts[7]);   // heading_deg

                        Map<String, Object> point = new HashMap<>();
                        point.put("lat", latitude);
                        point.put("lng", longitude);
                        point.put("ts", timestamp);
                        point.put("heading", heading);
                        point.put("speed", speed);
                        trackPoints.add(point);
                    } catch (NumberFormatException e) {
                        e.printStackTrace();
                    }
                }
            }
        }

        return trackPoints;
    }
}
