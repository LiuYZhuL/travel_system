package com.travel.travel_system.model.aggregate;

import com.travel.travel_system.model.*;

import java.util.List;

public class TripAggregate {
    private Trip trip;
    private TripBBox tripBBox;

    /** 原始轨迹点，用于回放、纠偏、再计算 */
    private List<TrackPoint> rawTrackPoints;

    /** 可渲染轨迹点，用于地图折线展示 */
    private List<TrackPoint> renderTrackPoints;

    /** 行程下全部照片 */
    private List<Photo> photos;

    /** 行程下全部视频 */
    private List<Video> videos;

    /** 照片/视频锚点 */
    private List<Anchor> anchors;

    /** 行程下地点摘要 */
    private List<PlaceSummary> places;

    /** 行程笔记 */
    private List<TripNote> notes;

    /** 故事块编排结果 */
    private List<StoryBlock> storyBlocks;

    /** AI 行程总结 */
    private TripAiSummary aiSummary;
}