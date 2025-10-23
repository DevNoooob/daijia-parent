package com.atguigu.daijia.driver.service.impl;

import com.atguigu.daijia.driver.config.TencentCloudProperties;
import com.atguigu.daijia.driver.service.CiService;
import com.atguigu.daijia.model.vo.order.TextAuditingVo;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.auth.COSCredentials;
import com.qcloud.cos.http.HttpProtocol;
import com.qcloud.cos.model.ciModel.auditing.*;
import com.qcloud.cos.region.Region;
import org.apache.commons.codec.binary.Base64;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

@Service
public class CiServiceImpl implements CiService {

    @Autowired
    private TencentCloudProperties tencentCloudProperties;


    @Override
    public Boolean imageAuditing(String path) {
        //1.创建任务请求对象
        ImageAuditingRequest request = new ImageAuditingRequest();
        //2.添加请求参数 参数详情请见 API 接口文档
        //2.1设置请求 bucket
        request.setBucketName("examplebucket-1250000000");
        //2.2设置审核策略 不传则为默认策略（预设）
        //request.setBizType("");
        //2.3设置 bucket 中的图片位置
        request.setObjectKey("1.png");
        //3.调用接口,获取任务响应对象
        COSClient client = this.getCOSClient();
        ImageAuditingResponse response = client.imageAuditing(request);
        return response.getResult().equals("0")
                && response.getAdsInfo().getHitFlag().equals("0")
                && response.getPornInfo().getHitFlag().equals("0")
                && response.getTerroristInfo().getHitFlag().equals("0")
                && response.getPoliticsInfo().getHitFlag().equals("0");
    }

    @Override
    public TextAuditingVo textAuditing(String content) {

        TextAuditingVo textAuditingVo = new TextAuditingVo();

        if (!StringUtils.hasText(content)) {
            textAuditingVo.setResult("0");
            return textAuditingVo;
        }
        COSClient client = this.getCOSClient();
        //1.创建任务请求对象
        TextAuditingRequest request = new TextAuditingRequest();
        //2.添加请求参数 参数详情请见 API 接口文档
        request.setBucketName(tencentCloudProperties.getBucketPrivate());
        //2.1.1设置请求内容,文本内容的Base64编码
        byte[] encoder = Base64.encodeBase64(content.getBytes());
        String contentBase64 = new String(encoder);
        request.getInput().setContent("Base64Str");
        request.getConf().setDetectType("all");

        //3.调用接口,获取任务响应对象
        TextAuditingResponse response = client.createAuditingTextJobs(request);
        AuditingJobsDetail detail = response.getJobsDetail();

        if ("Success".equals(detail.getState())) {
            //检测结果：0（审核正常） 1（判定为违规敏感内容） 2（疑似敏感，建议人工复核）
            String result = detail.getResult();

            //违规关键词
            StringBuffer keywords = new StringBuffer();
            List<SectionInfo> sectionInfoList = detail.getSectionList();
            for (SectionInfo sectionInfo : sectionInfoList) {
                String pornInfoKeyword = sectionInfo.getPornInfo().getKeywords();
                String illegalInfoKeyword = sectionInfo.getIllegalInfo().getKeywords();
                String abuseInfoKeyword = sectionInfo.getAbuseInfo().getKeywords();
                String adsInfoKeyword = sectionInfo.getAdsInfo().getKeywords();
                if (!pornInfoKeyword.isEmpty()) {
                    keywords.append(pornInfoKeyword).append(",");
                }
                if (!illegalInfoKeyword.isEmpty()) {
                    keywords.append(illegalInfoKeyword).append(",");
                }
                if (!abuseInfoKeyword.isEmpty()) {
                    keywords.append(abuseInfoKeyword).append(",");
                }
                if (!adsInfoKeyword.isEmpty()) {
                    keywords.append(adsInfoKeyword).append(",");
                }
            }
            textAuditingVo.setResult(result);
            textAuditingVo.setKeywords(keywords.toString());

        }
        return textAuditingVo;
    }

    public COSClient getCOSClient() {
        String secretId = tencentCloudProperties.getSecretId();
        String secretKey = tencentCloudProperties.getSecretKey();
        COSCredentials cred = new BasicCOSCredentials(secretId, secretKey);
        //设置bucket的地域，cos的地域
        Region region = new Region(tencentCloudProperties.getRegion());
        ClientConfig clientConfig = new ClientConfig(region);
        clientConfig.setHttpProtocol(HttpProtocol.https);
        return new COSClient(cred, clientConfig);
    }
}
