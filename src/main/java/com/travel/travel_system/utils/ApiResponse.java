package com.travel.travel_system.utils;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.ALWAYS)
public class ApiResponse<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 业务状态码，"0" 表示成功
     */
    private String code;

    /**
     * 提示信息
     */
    private String message;

    /**
     * 返回数据
     */
    private T data;

    /**
     * 请求追踪 ID
     */
    private String requestId;

    public static <T> ApiResponse<T> success(T data) {
        return ApiResponse.<T>builder()
                .code("0")
                .message("success")
                .data(data)
                .requestId(generateRequestId())
                .build();
    }

    public static <T> ApiResponse<T> success() {
        return ApiResponse.<T>builder()
                .code("0")
                .message("success")
                .data(null)
                .requestId(generateRequestId())
                .build();
    }

    public static <T> ApiResponse<T> error(String code, String message) {
        return ApiResponse.<T>builder()
                .code(code)
                .message(message)
                .data(null)
                .requestId(generateRequestId())
                .build();
    }

    public static <T> ApiResponse<T> error(String code, String message, T data) {
        return ApiResponse.<T>builder()
                .code(code)
                .message(message)
                .data(data)
                .requestId(generateRequestId())
                .build();
    }

    private static String generateRequestId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}