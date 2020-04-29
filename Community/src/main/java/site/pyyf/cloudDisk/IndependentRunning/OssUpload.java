package site.pyyf.cloudDisk.IndependentRunning;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClient;
import site.pyyf.community.entity.UploadResult;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import java.io.File;
import java.io.FileInputStream;


@Setter
@Getter
@NoArgsConstructor
@AllArgsConstructor
class AliyunConfigg {

    private String endpoint = "oss-cn-hangzhou.aliyuncs.com";
    private String accessKeyId ="LTAI4FjNL89Wwnd9eDhZEmgK";
    private String accessKeySecret="oAsudTksOBHo1HuekrSOWPEYAMmWQJ";
    private String bucketName="pyyf";
    private String urlPrefix="https://pyyf.oss-cn-hangzhou.aliyuncs.com/";

    public OSS oSSClient() {
        return new OSSClient(endpoint, accessKeyId, accessKeySecret);
    }

}

public class OssUpload {

    private static final Logger logger= LoggerFactory.getLogger(OssUpload.class);
    private OSS ossClient;
    private AliyunConfigg aliyunConfig;
    //创建 SingleObject 的一个对象
    private static OssUpload instance = new OssUpload();

    //让构造函数为 private，这样该类就不会被实例化
    private OssUpload(){
        aliyunConfig = new AliyunConfigg();
        ossClient = aliyunConfig.oSSClient();
    }

    //获取唯一可用的对象
    public static OssUpload getInstance(){
        return instance;
    }

    // 允许上传的格式
    private static String[] SUP_TYPE = new String[]{
            "md", "java", "css", "cpp", "py", "php", "html",
            "bmp", "jpg", "jpeg", "gif", "png",
            "mp4", "wmv", "flv",
            "mp3", "wma"};

    private String getFilePath(String suffix,String fileName) {

        return  suffix + "/" +fileName ;
    }


    public UploadResult upload(String suffix, File file) {
        String fileName = file.getName();
        // 校验文件格式
        boolean isLegal = false;
        for (String type : SUP_TYPE) {
            if (StringUtils.substringAfterLast(fileName, ".").equals(type)) {
                isLegal = true;
                break;
            }
        }
        // 封装Result对象，并且将文件的byte数组放置到result对象中
        UploadResult fileUploadResult = new UploadResult();
        if (!isLegal) {
            fileUploadResult.setMessage("上传OSS时后缀名不匹配");
            fileUploadResult.setSuccess(0);
            logger.error("上传OSS时后缀名不匹配");
            return fileUploadResult;
        }

        String remotePath = getFilePath(suffix, fileName);
        // 上传到阿里云
        try {
            ossClient.putObject(aliyunConfig.getBucketName(), remotePath, new FileInputStream(file));
            logger.info("上传OSS成功");
        } catch (Exception e) {
            e.printStackTrace();
            logger.error("上传OSS失败");

            //上传失败
            fileUploadResult.setMessage("服务器发生了错误");
            fileUploadResult.setSuccess(0);
            return fileUploadResult;
        }
        fileUploadResult.setSuccess(1);
        fileUploadResult.setMessage("上传成功");
        fileUploadResult.setUrl(this.aliyunConfig.getUrlPrefix() + remotePath);
        return fileUploadResult;
    }


}
