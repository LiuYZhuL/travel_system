package com.travel.travel_system.exception;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum ErrorCode {
    SYSTEM_500("SYSTEM_500", "系统错误"),
    SYSTEM_400("SYSTEM_400", "请求参数错误"),
    
    AUTH_001("AUTH_001", "未授权"),
    AUTH_002("AUTH_002", "登录已过期"),
    AUTH_003("AUTH_003", "登录失败"),
    AUTH_004("AUTH_004", "用户不存在"),
    
    TRIP_001("TRIP_001", "行程不存在"),
    TRIP_002("TRIP_002", "行程状态异常"),
    TRIP_003("TRIP_003", "行程已结束"),
    TRIP_004("TRIP_004", "行程未开始"),
    TRIP_005("TRIP_005", "行程进行中"),
    TRIP_006("TRIP_006", "已有进行中的行程"),
    
    TRACK_001("TRACK_001", "轨迹点数据为空"),
    TRACK_002("TRACK_002", "轨迹匹配失败"),
    
    MEDIA_001("MEDIA_001", "媒体文件不存在"),
    MEDIA_002("MEDIA_002", "媒体上传失败"),
    MEDIA_003("MEDIA_003", "不支持的媒体类型"),
    
    USER_001("USER_001", "用户不存在"),
    USER_002("USER_002", "用户信息更新失败");

    private final String code;
    private final String message;
}
