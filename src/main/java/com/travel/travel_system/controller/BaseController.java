package com.travel.travel_system.controller;

import com.travel.travel_system.utils.ApiResponse;
import com.travel.travel_system.utils.PageData;

import java.util.List;

public abstract class BaseController {

    protected <T> ApiResponse<T> success(T data) {
        return ApiResponse.success(data);
    }

    protected ApiResponse<Void> success() {
        return ApiResponse.success();
    }

    protected <T> ApiResponse<T> error(String code, String message) {
        return ApiResponse.error(code, message);
    }

    protected <T> ApiResponse<T> error(String code, String message, T data) {
        return ApiResponse.error(code, message, data);
    }

    protected <T> ApiResponse<PageData<T>> page(List<T> list, Integer pageNo, Integer pageSize, Long total) {
        return ApiResponse.success(PageData.of(list, pageNo, pageSize, total));
    }
}