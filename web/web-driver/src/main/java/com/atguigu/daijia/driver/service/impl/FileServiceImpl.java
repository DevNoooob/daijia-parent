package com.atguigu.daijia.driver.service.impl;

import com.atguigu.daijia.common.execption.GuiguException;
import com.atguigu.daijia.common.result.ResultCodeEnum;
import com.atguigu.daijia.driver.config.MinioProperties;
import com.atguigu.daijia.driver.service.FileService;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.UUID;

@Slf4j
@Service
@SuppressWarnings({"unchecked", "rawtypes"})
public class FileServiceImpl implements FileService {

    @Autowired
    private MinioProperties minioProperties;

    @Override
    public String upload(MultipartFile file) {
        try {
            if (file == null || file.isEmpty()) {
                throw new GuiguException(ResultCodeEnum.DATA_ERROR.getCode(), "上传文件为空");
            }

            MinioClient minioClient = MinioClient.builder()
                    .endpoint(minioProperties.getEndpointUrl())
                    .credentials(minioProperties.getAccessKey(), minioProperties.getSecretKey())
                    .build();

            if (!minioClient.bucketExists(BucketExistsArgs.builder().bucket(minioProperties.getBucketName()).build())) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(minioProperties.getBucketName()).build());
            }

            String originalName = file.getOriginalFilename();
            String ext = originalName.substring(originalName.lastIndexOf('.') + 1);
            String fileName = new SimpleDateFormat("yyyyMMdd").format(new Date()) + "/"
                    + UUID.randomUUID().toString().replace("-", "") + "." + ext;

            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(minioProperties.getBucketName())
                    .object(fileName)
                    .stream(file.getInputStream(), file.getSize(), -1)
                    .contentType(file.getContentType())
                    .build());

            String url = minioProperties.getEndpointUrl() + "/" + minioProperties.getBucketName() + "/" + fileName;
            log.info("文件上传成功: {}", url);
            return url;

        } catch (Exception e) {
            log.error("文件上传失败: {}", e.getMessage(), e);
            throw new GuiguException(ResultCodeEnum.DATA_ERROR.getCode(), "上传失败：" + e.getMessage());
        }
    }

}
