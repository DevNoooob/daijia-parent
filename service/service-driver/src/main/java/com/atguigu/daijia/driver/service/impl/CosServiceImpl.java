package com.atguigu.daijia.driver.service.impl;

import com.atguigu.daijia.common.execption.GuiguException;
import com.atguigu.daijia.common.result.ResultCodeEnum;
import com.atguigu.daijia.driver.config.TencentCloudProperties;
import com.atguigu.daijia.driver.service.CiService;
import com.atguigu.daijia.driver.service.CosService;
import com.atguigu.daijia.model.vo.driver.CosUploadVo;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.auth.BasicSessionCredentials;
import com.qcloud.cos.auth.COSCredentials;
import com.qcloud.cos.http.HttpMethodName;
import com.qcloud.cos.model.*;
import com.qcloud.cos.region.Region;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.joda.time.DateTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.Date;
import java.util.UUID;

@Slf4j
@Service
@SuppressWarnings({"unchecked", "rawtypes"})
public class CosServiceImpl implements CosService {

    @Autowired
    private TencentCloudProperties tencentCloudProperties;

    @Autowired
    private CiService ciService;

    public COSClient getCOSClient() {
        // 1 初始化用户身份信息(SecretId，getSecretKey)
        String tmpSecretId = tencentCloudProperties.getSecretId();
        String tmpSecretKey = tencentCloudProperties.getSecretKey();
        COSCredentials cred = new BasicCOSCredentials(tmpSecretId, tmpSecretKey);

        // 2 设置 bucket 的地域
        Region region = new Region(tencentCloudProperties.getRegion()); //COS_REGION 参数：配置成存储桶 bucket 的实际地域，例如 ap-beijing，更多 COS 地域的简称请参见 https://cloud.tencent.com/document/product/436/6224
        ClientConfig clientConfig = new ClientConfig(region);

        // 3 生成 cos 客户端
        COSClient cosClient = new COSClient(cred, clientConfig);

        return cosClient;
    }

    @Override
    public CosUploadVo upload(MultipartFile file, String path) {

        COSClient cosClient = this.getCOSClient();

        //文件上传
        //元数据信息
        ObjectMetadata objectMetadata = new ObjectMetadata();
        objectMetadata.setContentLength(file.getSize());
        objectMetadata.setContentEncoding("UTF-8");
        objectMetadata.setContentType(file.getContentType());

        //向存储桶中保存文件
        String fileType = file.getOriginalFilename().substring(file.getOriginalFilename().lastIndexOf("."));
        String uploadPath = "/driver" + path + "/" + UUID.randomUUID().toString().replaceAll("-", "") + fileType;


        // 指定要上传的文件file
        PutObjectRequest putObjectRequest = null;
        try {
            putObjectRequest = new PutObjectRequest(tencentCloudProperties.getBucketPrivate(), uploadPath, file.getInputStream(),objectMetadata);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        putObjectRequest.setStorageClass(StorageClass.Standard);
        PutObjectResult putObjectResult = cosClient.putObject(putObjectRequest);

        Boolean imageAuditing = ciService.imageAuditing(uploadPath);
        if (!imageAuditing) {
            cosClient.deleteObject(tencentCloudProperties.getBucketPrivate(), uploadPath);
            throw new GuiguException(ResultCodeEnum.IMAGE_AUDITION_FAIL);
        }

        cosClient.shutdown();

        CosUploadVo cosUploadVo = new CosUploadVo();
        cosUploadVo.setUrl(uploadPath);
        // 图片临时访问URL，回显使用
        String imageUrl = this.getImageUrl(uploadPath);
        cosUploadVo.setShowUrl(imageUrl);
        return cosUploadVo;
    }

    @Override
    public String getImageUrl(String path) {
        if (!StringUtils.hasText(path)) return "";
        //获取COSClient
        COSClient cosClient = this.getCOSClient();
        GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest(tencentCloudProperties.getBucketPrivate(), path, HttpMethodName.GET);
        //设置URL有效期为15min
        Date date = new DateTime().plusMinutes(15).toDate();
        request.setExpiration(date);
        URL url = cosClient.generatePresignedUrl(request);

        cosClient.shutdown();

        return url.toString();
    }
}
