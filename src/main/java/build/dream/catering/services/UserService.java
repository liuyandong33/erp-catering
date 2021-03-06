package build.dream.catering.services;

import build.dream.catering.constants.Constants;
import build.dream.catering.mappers.BranchMapper;
import build.dream.catering.models.user.AddUserModel;
import build.dream.catering.models.user.ListUsersModel;
import build.dream.catering.models.user.ObtainBranchInfoModel;
import build.dream.catering.models.user.ObtainUserInfoModel;
import build.dream.catering.utils.SequenceUtils;
import build.dream.common.api.ApiRest;
import build.dream.common.catering.domains.Branch;
import build.dream.common.saas.domains.SystemUser;
import build.dream.common.utils.*;
import org.apache.commons.collections.MapUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class UserService {
    @Autowired
    private BranchMapper branchMapper;

    @Transactional(readOnly = true)
    public ApiRest listUsers(ListUsersModel listUsersModel) {
        BigInteger tenantId = listUsersModel.obtainTenantId();
        BigInteger branchId = listUsersModel.obtainBranchId();
        Integer page = listUsersModel.getPage();
        Integer rows = listUsersModel.getRows();

        List<SearchCondition> searchConditions = new ArrayList<SearchCondition>();
        searchConditions.add(new SearchCondition("tenant_id", Constants.SQL_OPERATION_SYMBOL_EQUAL, tenantId));
        searchConditions.add(new SearchCondition("branch_id", Constants.SQL_OPERATION_SYMBOL_EQUAL, branchId));
        searchConditions.add(new SearchCondition("delete", Constants.SQL_OPERATION_SYMBOL_EQUAL, 0));

        long count = branchMapper.countUsers(searchConditions);

        List<Map<String, Object>> userInfos = null;
        if (count > 0) {
            List<BigInteger> userIds = branchMapper.findAllUserIds(searchConditions, (page - 1) * rows, rows);
            userInfos = UserUtils.batchGetUsers(userIds);
        } else {
            userInfos = new ArrayList<Map<String, Object>>();
        }

        Map<String, Object> data = new HashMap<String, Object>();
        data.put("total", count);
        data.put("rows", userInfos);
        return ApiRest.builder().data(data).message("查询员工列表成功！").successful(true).build();
    }

    @Transactional(readOnly = true)
    public ApiRest obtainUserInfo(ObtainUserInfoModel obtainUserInfoModel) {
        BigInteger userId = obtainUserInfoModel.obtainUserId();
        Map<String, Object> userInfo = UserUtils.obtainUserInfo(userId);

        Map<String, Object> data = new HashMap<String, Object>(userInfo);
        Map<String, Object> user = MapUtils.getMap(userInfo, "user");
        BigInteger tenantId = BigInteger.valueOf(MapUtils.getLongValue(user, "tenantId"));

        Branch branch = branchMapper.findByTenantIdAndUserId(tenantId, userId);
        ValidateUtils.notNull(branch, "门店信息不存在！");

        data.put("branch", branch);
        return ApiRest.builder().data(data).message("获取用户信息成功！").successful(true).build();
    }

    /**
     * 获取门店信息
     *
     * @param obtainBranchInfoModel
     * @return
     */
    @Transactional(readOnly = true)
    public ApiRest obtainBranchInfo(ObtainBranchInfoModel obtainBranchInfoModel) {
        BigInteger tenantId = obtainBranchInfoModel.getTenantId();
        BigInteger userId = obtainBranchInfoModel.getUserId();
        Branch branch = branchMapper.findByTenantIdAndUserId(tenantId, userId);
        ValidateUtils.notNull(branch, "门店不存在！");

        return ApiRest.builder().data(branch).className(Branch.class.getName()).message("获取门店信息成功！").successful(true).build();
    }

    /**
     * 增加用户
     *
     * @param addUserModel
     * @return
     */
    @Transactional(rollbackFor = Exception.class)
    public ApiRest addUser(AddUserModel addUserModel) {
        BigInteger tenantId = addUserModel.obtainTenantId();
        String tenantCode = addUserModel.obtainTenantCode();
        BigInteger branchId = addUserModel.getBranchId();
        String name = addUserModel.getName();
        String mobile = addUserModel.getMobile();
        String email = addUserModel.getEmail();
        String password = addUserModel.getPassword();
        boolean enabled = addUserModel.getEnabled();
        BigInteger userId = addUserModel.obtainUserId();

        String employeeCode = SerialNumberGenerator.nextSerialNumber(4, SequenceUtils.nextValue(tenantCode + "_employee_code"));
        String loginName = tenantCode + ":" + employeeCode;

        Map<String, String> requestParameters = new HashMap<String, String>();
        requestParameters.put("name", name);
        requestParameters.put("mobile", mobile);
        requestParameters.put("email", email);
        requestParameters.put("loginName", loginName);
        requestParameters.put("userType", Constants.USER_TYPE_TENANT_EMPLOYEE.toString());
        requestParameters.put("password", password);
        requestParameters.put("tenantId", tenantId.toString());
        requestParameters.put("enabled", String.valueOf(enabled));
        requestParameters.put("userId", userId.toString());

        ApiRest addUserResult = ProxyUtils.doPostWithRequestParameters(Constants.SERVICE_NAME_PLATFORM, "user", "addUser", requestParameters);
        ValidateUtils.isTrue(addUserResult.isSuccessful(), addUserResult.getError());
        SystemUser systemUser = (SystemUser) addUserResult.getData();

        branchMapper.insertMergeUserBranch(systemUser.getId(), tenantId, tenantCode, branchId, userId, "增加员工，设置员工门店所属关系！");

        return ApiRest.builder().data(systemUser).message("增加用户成功！").successful(true).build();
    }
}
