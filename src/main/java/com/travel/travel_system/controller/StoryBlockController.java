package com.travel.travel_system.controller;

import com.travel.travel_system.model.StoryBlock;
import com.travel.travel_system.repository.StoryBlockRepository;
import com.travel.travel_system.service.AiService;
import com.travel.travel_system.utils.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/v1")
public class StoryBlockController extends BaseController {

    @Autowired
    private StoryBlockRepository storyBlockRepository;

    @Autowired
    private AiService aiService;

    @PostMapping("/trips/{tripId}/story-blocks")
    public ApiResponse<?> createStoryBlock(@PathVariable Long tripId,
                                           @RequestBody Map<String, Object> request,
                                           HttpServletRequest httpRequest) {
        try {
            Long userId = requireUserId(httpRequest);
            String blockType = asString(request.get("blockType"));
            if (blockType == null || blockType.trim().isEmpty()) {
                return error("VALID_001", "blockType 不能为空");
            }
            
            StoryBlock block = aiService.generateStoryBlock(tripId, blockType, request);
            return success(toBlockVO(block));
        } catch (Exception e) {
            return error("SYSTEM_500", "创建故事块失败：" + e.getMessage());
        }
    }

    @PatchMapping("/story-blocks/{blockId}")
    public ApiResponse<?> updateStoryBlock(@PathVariable Long blockId,
                                           @RequestBody Map<String, Object> request,
                                           HttpServletRequest httpRequest) {
        try {
            Long userId = requireUserId(httpRequest);
            StoryBlock block = storyBlockRepository.findById(blockId)
                    .orElseThrow(() -> new RuntimeException("故事块不存在"));
            
            if (!block.getUserId().equals(userId)) {
                return error("AUTH_003", "无权修改此故事块");
            }
            
            String title = asString(request.get("title"));
            String textContent = asString(request.get("textContent"));
            Boolean isHidden = asBoolean(request.get("isHidden"));
            Integer sortIndex = asInteger(request.get("sortIndex"));
            
            if (title != null) {
                block.setTitle(title);
            }
            if (textContent != null) {
                block.setTextContent(textContent);
            }
            if (isHidden != null) {
                block.setIsHidden(isHidden);
            }
            if (sortIndex != null) {
                block.setSortIndex(sortIndex);
            }
            block.setUpdatedAt(new Date());
            
            StoryBlock saved = storyBlockRepository.save(block);
            return success(toBlockVO(saved));
        } catch (Exception e) {
            return error("SYSTEM_500", "修改故事块失败：" + e.getMessage());
        }
    }

    @DeleteMapping("/story-blocks/{blockId}")
    public ApiResponse<?> deleteStoryBlock(@PathVariable Long blockId, HttpServletRequest httpRequest) {
        try {
            Long userId = requireUserId(httpRequest);
            StoryBlock block = storyBlockRepository.findById(blockId)
                    .orElseThrow(() -> new RuntimeException("故事块不存在"));
            
            if (!block.getUserId().equals(userId)) {
                return error("AUTH_003", "无权删除此故事块");
            }
            
            storyBlockRepository.delete(block);
            return success(Map.of("blockId", blockId));
        } catch (Exception e) {
            return error("SYSTEM_500", "删除故事块失败：" + e.getMessage());
        }
    }

    @PatchMapping("/story-blocks/{blockId}/visibility")
    public ApiResponse<?> toggleBlockVisibility(@PathVariable Long blockId,
                                                @RequestBody Map<String, Object> request,
                                                HttpServletRequest httpRequest) {
        try {
            Long userId = requireUserId(httpRequest);
            StoryBlock block = storyBlockRepository.findById(blockId)
                    .orElseThrow(() -> new RuntimeException("故事块不存在"));
            
            if (!block.getUserId().equals(userId)) {
                return error("AUTH_003", "无权修改此故事块");
            }
            
            Boolean isHidden = asBoolean(request.get("isHidden"));
            if (isHidden == null) {
                isHidden = !Boolean.TRUE.equals(block.getIsHidden());
            }
            
            block.setIsHidden(isHidden);
            block.setUpdatedAt(new Date());
            
            StoryBlock saved = storyBlockRepository.save(block);
            return success(toBlockVO(saved));
        } catch (Exception e) {
            return error("SYSTEM_500", "切换可见性失败：" + e.getMessage());
        }
    }

    @PostMapping("/trips/{tripId}/story-blocks/reorder")
    public ApiResponse<?> reorderStoryBlocks(@PathVariable Long tripId,
                                             @RequestBody Map<String, Object> request,
                                             HttpServletRequest httpRequest) {
        try {
            Long userId = requireUserId(httpRequest);
            
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> orders = (List<Map<String, Object>>) request.get("orders");
            if (orders == null || orders.isEmpty()) {
                return error("VALID_002", "orders 不能为空");
            }
            
            List<StoryBlock> blocks = storyBlockRepository.findByTripIdOrderBySortTimeAscSortIndexAsc(tripId);
            Map<Long, StoryBlock> blockMap = new HashMap<>();
            for (StoryBlock block : blocks) {
                if (!block.getUserId().equals(userId)) {
                    return error("AUTH_003", "无权修改此行程的故事块");
                }
                blockMap.put(block.getId(), block);
            }
            
            for (Map<String, Object> order : orders) {
                Long blockId = asLong(order.get("blockId"));
                Integer sortIndex = asInteger(order.get("sortIndex"));
                
                if (blockId == null || sortIndex == null) continue;
                
                StoryBlock block = blockMap.get(blockId);
                if (block != null) {
                    block.setSortIndex(sortIndex);
                    block.setUpdatedAt(new Date());
                }
            }
            
            List<StoryBlock> saved = storyBlockRepository.saveAll(blocks);
            return success(saved.stream().map(this::toBlockVO).toList());
        } catch (Exception e) {
            return error("SYSTEM_500", "重排序故事块失败：" + e.getMessage());
        }
    }

    private Long requireUserId(HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        if (userId == null) {
            throw new RuntimeException("用户不存在或未授权");
        }
        return userId;
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Long asLong(Object value) {
        if (value == null) return null;
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Integer asInteger(Object value) {
        if (value == null) return null;
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Boolean asBoolean(Object value) {
        if (value == null) return null;
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private Map<String, Object> toBlockVO(StoryBlock block) {
        Map<String, Object> vo = new LinkedHashMap<>();
        vo.put("id", block.getId());
        vo.put("tripId", block.getTripId());
        vo.put("blockType", block.getBlockType() != null ? block.getBlockType().name() : null);
        vo.put("title", block.getTitle());
        vo.put("textContent", block.getTextContent());
        vo.put("sortIndex", block.getSortIndex());
        vo.put("isHidden", block.getIsHidden());
        vo.put("refType", block.getRefType());
        vo.put("refId", block.getRefId());
        vo.put("coverObjectKey", block.getCoverObjectKey());
        return vo;
    }
}
