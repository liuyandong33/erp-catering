package build.dream.catering.controllers;

import build.dream.common.api.ApiRest;
import build.dream.common.controllers.BasicController;
import build.dream.common.utils.ApplicationHandler;
import build.dream.common.utils.GsonUtils;
import build.dream.common.utils.LogUtils;
import build.dream.catering.services.BranchService;
import org.apache.commons.lang.Validate;
import org.apache.commons.lang.math.NumberUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.math.BigInteger;
import java.util.Map;

@Controller
@RequestMapping(value = "/branch")
public class BranchController extends BasicController {
    @Autowired
    private BranchService branchService;

    @RequestMapping(value = "/initializeBranch")
    @ResponseBody
    public String initializeBranch() {
        ApiRest apiRest = null;
        Map<String, String> requestParameters = ApplicationHandler.getRequestParameters();
        try {
            String userId = requestParameters.get("userId");
            Validate.notNull(userId, "参数(userId)不能为空！");

            String tenantId = requestParameters.get("tenantId");
            Validate.notNull(tenantId, "参数(tenantId)不能为空！");

            String tenantCode = requestParameters.get("tenantCode");
            Validate.notNull(tenantCode, "参数(tenantCode)不能为空！");

            apiRest = branchService.initializeBranch(BigInteger.valueOf(Long.valueOf(userId)), BigInteger.valueOf(Long.valueOf(tenantId)), tenantCode);
        } catch (Exception e) {
            LogUtils.error("初始化门店失败", controllerSimpleName, "initializeBranch", e, requestParameters);
            apiRest = new ApiRest(e);
        }
        return GsonUtils.toJson(apiRest);
    }

    @RequestMapping(value = "/listBranches")
    @ResponseBody
    public String listBranches() {
        ApiRest apiRest = null;
        Map<String, String> requestParameters = ApplicationHandler.getRequestParameters();
        try {
            apiRest = branchService.listBranches(requestParameters);
        } catch (Exception e) {
            LogUtils.error("查询门店列表失败", controllerSimpleName, "listBranches", e, requestParameters);
            apiRest = new ApiRest(e);
        }
        return GsonUtils.toJson(apiRest);
    }

    @RequestMapping(value = "/findBranchInfoById")
    @ResponseBody
    public String findBranchInfoById() {
        ApiRest apiRest = null;
        Map<String, String> requestParameters = ApplicationHandler.getRequestParameters();
        try {
            String tenantId = requestParameters.get("tenantId");
            Validate.notNull(tenantId, ApplicationHandler.obtainParameterErrorMessage("tenantId"));

            String branchId = requestParameters.get("branchId");
            Validate.notNull(branchId, ApplicationHandler.obtainParameterErrorMessage("branchId"));
            apiRest = branchService.findBranchInfo(NumberUtils.createBigInteger(tenantId), NumberUtils.createBigInteger(branchId));
        } catch (Exception e) {
            LogUtils.error("查询门店失败", controllerSimpleName, "listBranches", e, requestParameters);
            apiRest = new ApiRest(e);
        }
        return GsonUtils.toJson(apiRest);
    }

    @RequestMapping(value = "/findAllBranchInfos")
    @ResponseBody
    public String findAllBranchInfos() {
        ApiRest apiRest = null;
        Map<String, String> requestParameters = ApplicationHandler.getRequestParameters();
        try {
            apiRest = branchService.findAllBranchInfos();
        } catch (Exception e) {
            LogUtils.error("查询门店失败", controllerSimpleName, "findAllBranchInfos", e, requestParameters);
            apiRest = new ApiRest(e);
        }
        return GsonUtils.toJson(apiRest);
    }

    @RequestMapping(value = "/findBranchInfo")
    @ResponseBody
    public String findBranchInfo() {
        ApiRest apiRest = null;
        Map<String, String> requestParameters = ApplicationHandler.getRequestParameters();
        try {
            String tenantId = requestParameters.get("tenantId");
            Validate.notNull(tenantId, ApplicationHandler.obtainParameterErrorMessage("tenantId"));

            String userId = requestParameters.get("userId");
            Validate.notNull(userId, ApplicationHandler.obtainParameterErrorMessage("userId"));
            apiRest = branchService.findBranchInfo(NumberUtils.createBigInteger(tenantId), NumberUtils.createBigInteger(userId));
        } catch (Exception e) {
            LogUtils.error("查询门店信息失败", controllerSimpleName, "findAllBranchInfos", e, requestParameters);
            apiRest = new ApiRest(e);
        }
        return GsonUtils.toJson(apiRest);
    }
}
