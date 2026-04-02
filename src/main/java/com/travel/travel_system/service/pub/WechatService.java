package com.travel.travel_system.service.pub;

import com.alibaba.fastjson.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

@Service
public class WechatService {

    @Value("${wechat.appid}")
    private String appId;

    @Value("${wechat.appsecret}")
    private String appSecret;

    @Value("${wechat.login-url}")
    private String loginUrl;

    private final RestTemplate restTemplate = new RestTemplate();

    private static final Logger logger = LoggerFactory.getLogger(WechatService.class);

    /**
     * 通过code获取微信用户信息
     */
    public JSONObject getWechatSession(String code) {
        String url = loginUrl + "?appid={appid}&secret={secret}&js_code={js_code}&grant_type=authorization_code";
        
        HttpHeaders headers = new HttpHeaders();
        HttpEntity<String> entity = new HttpEntity<>(headers);
        
        Map<String, String> params = new HashMap<>();
        params.put("appid", appId);
        params.put("secret", appSecret);
        params.put("js_code", code);
        params.put("grant_type", "authorization_code");
        
        try {
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class, params);

            JSONObject result = JSONObject.parseObject(response.getBody());
            if (result.containsKey("errcode")) {
                logger.error("微信 API 调用失败：errcode={}, errmsg={}",
                        result.getInteger("errcode"), result.getString("errmsg"));
                throw new RuntimeException("微信 API 调用失败：" + result.getString("errmsg"));
            }
            return result;
        } catch (Exception e) {
            // 返回错误信息
            JSONObject error = new JSONObject();
            error.put("errcode", 500);
            error.put("errmsg", "微信API调用失败: " + e.getMessage());
            return error;
        }
    }
}