package com.travel.travel_system.controller;

import com.travel.travel_system.utils.ApiResponse;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class NoteController extends BaseController {

    /**
     * 创建旅程笔记
     */
    @PostMapping("/trips/{tripId}/notes")
    public ApiResponse<?> createNote(@PathVariable Long tripId, @RequestBody Map<String, Object> request) {
        return error("SYSTEM_501", "创建旅程笔记接口暂未实现");
    }

    /**
     * 获取笔记列表
     */
    @GetMapping("/trips/{tripId}/notes")
    public ApiResponse<?> getNoteList(@PathVariable Long tripId) {
        return error("SYSTEM_501", "获取笔记列表接口暂未实现");
    }

    /**
     * 修改笔记
     */
    @PatchMapping("/notes/{noteId}")
    public ApiResponse<?> updateNote(@PathVariable Long noteId, @RequestBody Map<String, Object> request) {
        return error("SYSTEM_501", "修改笔记接口暂未实现");
    }

    /**
     * 删除笔记
     */
    @DeleteMapping("/notes/{noteId}")
    public ApiResponse<?> deleteNote(@PathVariable Long noteId) {
        return error("SYSTEM_501", "删除笔记接口暂未实现");
    }
}
