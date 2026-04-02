package com.travel.travel_system.service.pub;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSException;
import com.aliyun.oss.model.CannedAccessControlList;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.PutObjectRequest;
import com.aliyun.oss.model.PutObjectResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

@Service
public class OssService {
    
    @Autowired
    private OSS ossClient;
    
    @Value("${oss.bucket-name}")
    private String bucketName;

    @Value("${oss.endpoint}")
    private String endpoint;

    private String getDomain() {
        return "https://" + bucketName + "." + endpoint;
    }
    /**
     * 上传文件到OSS
     */
    public String uploadFile(MultipartFile file, String folder) throws IOException {
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || originalFilename.isEmpty()) {
            throw new IllegalArgumentException("文件名不能为空");
        }

        // 安全提取扩展名
        String fileExtension = "";
        int lastDotIndex = originalFilename.lastIndexOf(".");
        if (lastDotIndex > 0) {
            fileExtension = originalFilename.substring(lastDotIndex);
        }

        String fileName = folder + "/" + UUID.randomUUID() + fileExtension;
        
        try (InputStream inputStream = file.getInputStream()) {
            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentType(file.getContentType());
            
            PutObjectRequest putObjectRequest = new PutObjectRequest(bucketName, fileName, inputStream, metadata);
            PutObjectResult result = ossClient.putObject(putObjectRequest);

            ossClient.setObjectAcl(bucketName, fileName, CannedAccessControlList.PublicRead);
            
            return getDomain() + "/" + fileName;
        } catch (OSSException e) {
            throw new RuntimeException("OSS上传失败: " + e.getMessage());
        }
    }
    
    /**
     * 上传字节数组到OSS
     */
    public String uploadFile(byte[] bytes, String fileName) {
        try (InputStream inputStream = new java.io.ByteArrayInputStream(bytes)) {
            PutObjectRequest putObjectRequest = new PutObjectRequest(bucketName, fileName, inputStream);
            PutObjectResult result = ossClient.putObject(putObjectRequest);

            ossClient.setObjectAcl(bucketName, fileName, CannedAccessControlList.PublicRead);

            return getDomain() + "/" + fileName;
        } catch (Exception e) {
            throw new RuntimeException("OSS上传失败: " + e.getMessage());
        }
    }
    
    /**
     * 删除OSS文件
     */
    public void deleteFile(String objectName) {
        try {
            ossClient.deleteObject(bucketName, objectName);
        } catch (OSSException e) {
            throw new RuntimeException("OSS删除失败: " + e.getMessage());
        }
    }
    
    /**
     * 生成唯一的文件名
     */
    public String generateFileName(String originalFilename, String folder) {
        String fileExtension = originalFilename.substring(originalFilename.lastIndexOf("."));
        return folder + "/" + UUID.randomUUID() + fileExtension;
    }
}
