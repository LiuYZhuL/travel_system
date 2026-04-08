package com.travel.travel_system.service.pub;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSException;
import com.aliyun.oss.model.CannedAccessControlList;
import com.aliyun.oss.model.OSSObject;
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
            ossClient.putObject(putObjectRequest);
            ossClient.setObjectAcl(bucketName, fileName, CannedAccessControlList.PublicRead);
            return getDomain() + "/" + fileName;
        } catch (OSSException e) {
            throw new RuntimeException("OSS上传失败: " + e.getMessage(), e);
        }
    }

    /**
     * 上传字节数组到OSS，fileName 为完整 objectName。
     */
    public String uploadFile(byte[] bytes, String fileName) {
        try (InputStream inputStream = new java.io.ByteArrayInputStream(bytes)) {
            PutObjectRequest putObjectRequest = new PutObjectRequest(bucketName, fileName, inputStream);
            PutObjectResult result = ossClient.putObject(putObjectRequest);
            ossClient.setObjectAcl(bucketName, fileName, CannedAccessControlList.PublicRead);
            return getDomain() + "/" + fileName;
        } catch (Exception e) {
            throw new RuntimeException("OSS上传失败: " + e.getMessage(), e);
        }
    }

    /**
     * 按 objectName 读取文件字节
     */
    public byte[] getFileBytes(String objectName) {
        try (OSSObject object = ossClient.getObject(bucketName, objectName);
             InputStream inputStream = object.getObjectContent()) {
            return inputStream.readAllBytes();
        } catch (Exception e) {
            throw new RuntimeException("OSS读取失败: " + e.getMessage(), e);
        }
    }

    /**
     * 按完整URL读取文件字节
     */
    public byte[] getFileBytesByUrl(String fileUrl) {
        return getFileBytes(extractObjectName(fileUrl));
    }

    /**
     * 从完整OSS URL中提取 objectName
     */
    public String extractObjectName(String fileUrl) {
        if (fileUrl == null || fileUrl.isBlank()) {
            throw new IllegalArgumentException("OSS文件URL不能为空");
        }
        String domainPrefix = getDomain() + "/";
        if (fileUrl.startsWith(domainPrefix)) {
            return fileUrl.substring(domainPrefix.length());
        }
        return fileUrl;
    }

    /**
     * 删除OSS文件，入参可为 objectName 或完整URL
     */
    public void deleteFile(String objectNameOrUrl) {
        try {
            ossClient.deleteObject(bucketName, extractObjectName(objectNameOrUrl));
        } catch (OSSException e) {
            throw new RuntimeException("OSS删除失败: " + e.getMessage(), e);
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
