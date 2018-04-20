package build.dream.catering.controllers;

import build.dream.catering.models.goods.ListGoodsesModel;
import build.dream.catering.models.weixin.CreateMemberCardModel;
import build.dream.catering.services.WeiXinService;
import build.dream.common.api.ApiRest;
import build.dream.common.controllers.BasicController;
import build.dream.common.utils.ApplicationHandler;
import build.dream.common.utils.GsonUtils;
import build.dream.common.utils.LogUtils;
import org.apache.commons.lang.Validate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;

import javax.servlet.http.HttpServletRequest;
import java.util.Map;

@Controller
@RequestMapping(value = "/weiXin")
public class WeiXinController extends BasicController {
    @Autowired
    private WeiXinService weiXinService;

    @RequestMapping(value = "/createMemberCard")
    @ResponseBody
    public String createMemberCard(HttpServletRequest httpServletRequest) {
        ApiRest apiRest = null;
        Map<String, String> requestParameters = ApplicationHandler.getRequestParameters(httpServletRequest);
        try {
            CreateMemberCardModel createMemberCardModel = ApplicationHandler.instantiateObject(CreateMemberCardModel.class, requestParameters);
            createMemberCardModel.validateAndThrow();

            Validate.isTrue(httpServletRequest instanceof MultipartHttpServletRequest, "请上传logo！");
            MultipartHttpServletRequest multipartHttpServletRequest = (MultipartHttpServletRequest) httpServletRequest;

            MultipartFile backgroundPicFile = multipartHttpServletRequest.getFile("backgroundPic");

            MultipartFile logoFile = multipartHttpServletRequest.getFile("logoFile");
            Validate.notNull(logoFile, "请上传logo！");

            apiRest = weiXinService.createMemberCard(createMemberCardModel, backgroundPicFile, logoFile);
        } catch (Exception e) {
            LogUtils.error("创建会员卡失败", controllerSimpleName, "createMemberCard", e, requestParameters);
            apiRest = new ApiRest(e);
        }
        return GsonUtils.toJson(apiRest);
    }
}
