package build.dream.catering.controllers;

import build.dream.catering.models.miniprogram.ObtainSessionWithJsCodeModel;
import build.dream.common.api.ApiRest;
import build.dream.common.controllers.BasicController;
import build.dream.common.utils.ApplicationHandler;
import build.dream.common.utils.MethodCaller;
import build.dream.common.utils.OutUtils;
import build.dream.common.utils.WebUtils;
import net.sf.json.JSONObject;
import org.apache.commons.lang.Validate;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.HashMap;
import java.util.Map;

@Controller
@RequestMapping(value = "/miniProgram")
public class MiniProgramController extends BasicController {
    @RequestMapping(value = "/obtainSessionWithJsCode")
    @ResponseBody
    public String obtainSessionWithJsCode() {
        Map<String, String> requestParameters = ApplicationHandler.getRequestParameters();
        MethodCaller methodCaller = () -> {
            ObtainSessionWithJsCodeModel obtainSessionWithJsCodeModel = ApplicationHandler.instantiateObject(ObtainSessionWithJsCodeModel.class, requestParameters);
            obtainSessionWithJsCodeModel.validateAndThrow();

            Map<String, String> obtainSessionRequestParameters = new HashMap<String, String>();
            obtainSessionRequestParameters.put("appid", "wxc375e036b110a28f");
            obtainSessionRequestParameters.put("secret", "415627f3ec7bf51497a98e94546affa7");
            obtainSessionRequestParameters.put("js_code", obtainSessionWithJsCodeModel.getCode());
            obtainSessionRequestParameters.put("grant_type", "authorization_code");

            String url = "https://api.weixin.qq.com/sns/jscode2session?" + WebUtils.buildQueryString(obtainSessionRequestParameters);
            String obtainSessionResult = OutUtils.doGet(url, null);
            JSONObject obtainSessionResultJsonObject = JSONObject.fromObject(obtainSessionResult);
            Validate.isTrue(!obtainSessionResultJsonObject.has("errcode"), obtainSessionResultJsonObject.optString("errmsg"));

            ApiRest apiRest = new ApiRest();
            apiRest.setData(obtainSessionResultJsonObject);
            apiRest.setMessage("code换取session_key成功！");
            apiRest.setSuccessful(true);
            return apiRest;
        };
        return ApplicationHandler.callMethod(methodCaller, "code换取session_key失败", requestParameters);
    }
}
