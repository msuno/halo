package run.halo.app.handler.file;


import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.auth.COSCredentials;
import com.qcloud.cos.auth.COSSigner;
import com.qcloud.cos.model.ObjectMetadata;
import com.qcloud.cos.model.PutObjectResult;
import com.qcloud.cos.region.Region;

import cn.hutool.core.date.DateUtil;
import lombok.extern.slf4j.Slf4j;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.web.multipart.MultipartFile;
import run.halo.app.exception.FileOperationException;
import run.halo.app.model.enums.AttachmentType;
import run.halo.app.model.properties.TencentCosProperties;
import run.halo.app.model.support.UploadResult;
import run.halo.app.service.OptionService;
import run.halo.app.utils.FilenameUtils;
import run.halo.app.utils.ImageUtils;

import javax.imageio.ImageReader;

import java.text.SimpleDateFormat;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TimeZone;

import static run.halo.app.model.support.HaloConst.URL_SEPARATOR;

/**
 * Tencent cos file handler.
 *
 * @author wangya
 * @author ryanwang
 * @date 2019-07-25
 */
@Slf4j
@Component
public class TencentCosFileHandler implements FileHandler {

    private final OptionService optionService;

    public TencentCosFileHandler(OptionService optionService) {
        this.optionService = optionService;
    }

    @Override
    public UploadResult upload(MultipartFile file) {
        Assert.notNull(file, "Multipart file must not be null");

        // Get config
        String protocol = optionService.getByPropertyOfNonNull(TencentCosProperties.COS_PROTOCOL).toString();
        String domain = optionService.getByPropertyOrDefault(TencentCosProperties.COS_DOMAIN, String.class, "");
        String region = optionService.getByPropertyOfNonNull(TencentCosProperties.COS_REGION).toString();
        String secretId = optionService.getByPropertyOfNonNull(TencentCosProperties.COS_SECRET_ID).toString();
        String secretKey = optionService.getByPropertyOfNonNull(TencentCosProperties.COS_SECRET_KEY).toString();
        String bucketName = optionService.getByPropertyOfNonNull(TencentCosProperties.COS_BUCKET_NAME).toString();
        String source = optionService.getByPropertyOrDefault(TencentCosProperties.COS_SOURCE, String.class, "");
        String styleRule = optionService.getByPropertyOrDefault(TencentCosProperties.COS_STYLE_RULE, String.class, "");
        String thumbnailStyleRule = optionService.getByPropertyOrDefault(TencentCosProperties.COS_THUMBNAIL_STYLE_RULE, String.class, "");

        COSCredentials cred = new BasicCOSCredentials(secretId, secretKey);
        Region regionConfig = new Region(region);
        ClientConfig clientConfig = new ClientConfig(regionConfig);

        // Init OSS client
        COSClient cosClient = new COSClient(cred, clientConfig);

        StringBuilder basePath = new StringBuilder(protocol);

        if (StringUtils.isNotEmpty(domain)) {
            basePath.append(domain)
                    .append(URL_SEPARATOR);
        } else {
            basePath.append(bucketName)
                    .append(".cos.")
                    .append(region)
                    .append(".myqcloud.com")
                    .append(URL_SEPARATOR);
        }

        try {
            String basename = FilenameUtils.getBasename(Objects.requireNonNull(file.getOriginalFilename()));
            String extension = FilenameUtils.getExtension(file.getOriginalFilename());
            String timestamp = String.valueOf(System.currentTimeMillis());
            StringBuilder upFilePath = new StringBuilder();

            if (StringUtils.isNotEmpty(source)) {
                upFilePath.append(source)
                        .append(URL_SEPARATOR);
            }

            upFilePath.append(basename)
                    .append("_")
                    .append(timestamp)
                    .append(".")
                    .append(extension);

            String filePath = StringUtils.join(basePath.toString(), upFilePath.toString());

            // Upload
            ObjectMetadata objectMetadata = new ObjectMetadata();
            //提前告知输入流的长度, 否则可能导致 oom
            objectMetadata.setContentLength(file.getSize());
            // 设置 Content type, 默认是 application/octet-stream
            objectMetadata.setContentType(file.getContentType());
            PutObjectResult putObjectResponseFromInputStream = cosClient.putObject(bucketName, upFilePath.toString(), file.getInputStream(), objectMetadata);
            if (putObjectResponseFromInputStream == null) {
                throw new FileOperationException("上传附件 " + file.getOriginalFilename() + " 到腾讯云失败 ");
            }

            // Response result
            UploadResult uploadResult = new UploadResult();
            uploadResult.setFilename(basename);
            uploadResult.setFilePath(StringUtils.isBlank(styleRule) ? filePath : filePath + styleRule);
            uploadResult.setKey(upFilePath.toString());
            uploadResult.setMediaType(MediaType.valueOf(Objects.requireNonNull(file.getContentType())));
            uploadResult.setSuffix(extension);
            uploadResult.setSize(file.getSize());

            // Handle thumbnail
            if (FileHandler.isImageType(uploadResult.getMediaType())) {
                ImageReader image = ImageUtils.getImageReaderFromFile(file.getInputStream(), extension);
                assert image != null;
                uploadResult.setWidth(image.getWidth(0));
                uploadResult.setHeight(image.getHeight(0));
                if (ImageUtils.EXTENSION_ICO.equals(extension)) {
                    uploadResult.setThumbPath(filePath);
                } else {
                    uploadResult.setThumbPath(StringUtils.isBlank(thumbnailStyleRule) ? filePath : filePath + thumbnailStyleRule);
                }
            }

            return uploadResult;
        } catch (Exception e) {
            throw new FileOperationException("附件 " + file.getOriginalFilename() + " 上传失败(腾讯云)", e);
        } finally {
            cosClient.shutdown();
        }
    }

    @Override
    public void delete(String key) {
        Assert.notNull(key, "File key must not be blank");

        // Get config
        String region = optionService.getByPropertyOfNonNull(TencentCosProperties.COS_REGION).toString();
        String secretId = optionService.getByPropertyOfNonNull(TencentCosProperties.COS_SECRET_ID).toString();
        String secretKey = optionService.getByPropertyOfNonNull(TencentCosProperties.COS_SECRET_KEY).toString();
        String bucketName = optionService.getByPropertyOfNonNull(TencentCosProperties.COS_BUCKET_NAME).toString();

        COSCredentials cred = new BasicCOSCredentials(secretId, secretKey);
        Region regionConfig = new Region(region);
        ClientConfig clientConfig = new ClientConfig(regionConfig);

        // Init OSS client
        COSClient cosClient = new COSClient(cred, clientConfig);

        try {
            cosClient.deleteObject(bucketName, key);
        } catch (Exception e) {
            throw new FileOperationException("附件 " + key + " 从腾讯云删除失败", e);
        } finally {
            cosClient.shutdown();
        }
    }

    @Override
    public AttachmentType getAttachmentType() {
        return AttachmentType.TENCENTCOS;
    }
    
    @Override
    public Map<String, String> getUploadToken() {
        String region = optionService.getByPropertyOfNonNull(TencentCosProperties.COS_REGION).toString();
        String secretId = optionService.getByPropertyOfNonNull(TencentCosProperties.COS_SECRET_ID).toString();
        String secretKey = optionService.getByPropertyOfNonNull(TencentCosProperties.COS_SECRET_KEY).toString();
        String bucketName = optionService.getByPropertyOfNonNull(TencentCosProperties.COS_BUCKET_NAME).toString();
        String source = optionService.getByPropertyOrDefault(TencentCosProperties.COS_SOURCE, String.class, "");
        String protocol = optionService.getByPropertyOfNonNull(TencentCosProperties.COS_PROTOCOL).toString();
        String domain = optionService.getByPropertyOrDefault(TencentCosProperties.COS_DOMAIN, String.class, "");
        StringBuilder basePath = new StringBuilder();
    
        if (StringUtils.isNotEmpty(source)) {
            basePath.append(source)
                    .append(URL_SEPARATOR);
        }
    
        StringBuilder host = new StringBuilder(protocol);
    
        if (StringUtils.isNotEmpty(domain)) {
            host.append(domain)
                    .append(URL_SEPARATOR);
        } else {
            host.append(bucketName)
                    .append(".cos.")
                    .append(region)
                    .append(".myqcloud.com")
                    .append(URL_SEPARATOR);
        }
        basePath.append(DateUtil.format(new Date(), "yyyyMMdd")).append(URL_SEPARATOR);
        String suffixPath = basePath.toString();
        COSCredentials cred = new BasicCOSCredentials(secretId, secretKey);
        COSSigner signer = new COSSigner();
        //设置过期时间为1个小时
        ZonedDateTime utc = ZonedDateTime.now(ZoneOffset.UTC);
        Long startTime = utc.toEpochSecond();
        Long endTime = startTime + 60 * 60;
        String keyTime = startTime.toString() + ";" + endTime.toString();
        // 要签名的 key, 生成的签名只能用于对应此 key 的上传
        SimpleDateFormat dateFormatGmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
        dateFormatGmt.setTimeZone(TimeZone.getTimeZone("GMT"));
        String policy = "{\"expiration\":\"" + dateFormatGmt.format(new Date(endTime * 1000)) +
                "\",\"conditions\":[{\"q-sign-algorithm\":\"sha1\"},{\"q-ak\":\"" + secretId + "\"},{\"q-sign-time\":\"" +
                keyTime + "\"},[ \"starts-with\", \"$key\", \"" + basePath + "\" ]]}";
        String sign = signer.buildPostObjectSignature(secretKey, keyTime, policy);
        Map<String, String> result = new HashMap<>();
        result.put("q-sign-algorithm", "sha1");
        result.put("policyO", policy);
        result.put("policy", Base64.encodeBase64String(policy.getBytes()));
        result.put("q-ak", secretId);
        result.put("q-key-time", keyTime);
        result.put("q-signature", sign);
        result.put("basePath", suffixPath);
        result.put("host", host.toString());
        return result;
    }
}
