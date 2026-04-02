package com.travel.travel_system.controller;

import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class NoteController {

    /**
     * 创建旅程笔记
     */
    @PostMapping("/trips/{tripId}/notes")
    public Map<String, Object> createNote(@PathVariable Long tripId, @RequestBody Map<String, Object> request) {
        return null;
    }

    /**
     * 获取笔记列表
     */
    @GetMapping("/trips/{tripId}/notes")
    public Map<String, Object> getNoteList(@PathVariable Long tripId) {
        return null;
    }

    /**
     * 修改笔记
     */
    @PatchMapping("/notes/{noteId}")
    public Map<String, Object> updateNote(@PathVariable Long noteId, @RequestBody Map<String, Object> request) {
        return null;
    }

    /**
     * 删除笔记
     */
    @DeleteMapping("/notes/{noteId}")
    public Map<String, Object> deleteNote(@PathVariable Long noteId) {
        return null;
    }
}
