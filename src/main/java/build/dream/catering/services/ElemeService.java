package build.dream.catering.services;

import build.dream.catering.constants.Constants;
import build.dream.catering.models.eleme.*;
import build.dream.catering.tools.PushMessageThread;
import build.dream.common.api.ApiRest;
import build.dream.common.catering.domains.*;
import build.dream.common.constants.DietOrderConstants;
import build.dream.common.models.jpush.PushModel;
import build.dream.common.utils.*;
import net.sf.json.JSONArray;
import net.sf.json.JSONNull;
import net.sf.json.JSONObject;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.math.NumberUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URLEncoder;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;

@Service
public class ElemeService {
    /**
     * 商户授权
     *
     * @param tenantAuthorizeModel
     * @return
     * @throws IOException
     */
    @Transactional(readOnly = true)
    public ApiRest tenantAuthorize(TenantAuthorizeModel tenantAuthorizeModel) throws IOException {
        BigInteger tenantId = tenantAuthorizeModel.obtainTenantId();
        BigInteger branchId = tenantAuthorizeModel.obtainBranchId();
        BigInteger userId = tenantAuthorizeModel.obtainUserId();
        String partitionCode = tenantAuthorizeModel.obtainPartitionCode();
        String clientType = tenantAuthorizeModel.obtainClientType();

        SearchModel searchModel = new SearchModel(true);
        searchModel.addSearchCondition(Branch.ColumnName.TENANT_ID, Constants.SQL_OPERATION_SYMBOL_EQUAL, tenantId);
        searchModel.addSearchCondition(Branch.ColumnName.ID, Constants.SQL_OPERATION_SYMBOL_EQUAL, branchId);
        Branch branch = DatabaseHelper.find(Branch.class, searchModel);
        ValidateUtils.notNull(branch, "门店不存在！");

        int elemeAccountType = branch.getElemeAccountType();
        String tokenField = null;
        if (elemeAccountType == Constants.ELEME_ACCOUNT_TYPE_CHAIN_ACCOUNT) {
            tokenField = Constants.ELEME_TOKEN + "_" + tenantId;
        } else if (elemeAccountType == Constants.ELEME_ACCOUNT_TYPE_INDEPENDENT_ACCOUNT) {
            tokenField = Constants.ELEME_TOKEN + "_" + tenantId + "_" + branchId;
        }

        boolean tokenIsExists = CacheUtils.hexists(Constants.KEY_ELEME_TOKENS, tokenField);
        boolean isAuthorize = false;
        if (tokenIsExists) {
            Map<String, String> verifyTokenRequestParameters = new HashMap<String, String>();
            verifyTokenRequestParameters.put("tenantId", tenantId.toString());
            verifyTokenRequestParameters.put("branchId", branchId.toString());
            verifyTokenRequestParameters.put("userId", userId.toString());
            verifyTokenRequestParameters.put("elemeAccountType", String.valueOf(elemeAccountType));
            ApiRest verifyTokenResult = ProxyUtils.doPostWithRequestParameters(Constants.SERVICE_NAME_PLATFORM, "eleme", "verifyToken", verifyTokenRequestParameters);
            ValidateUtils.isTrue(verifyTokenResult.isSuccessful(), verifyTokenResult.getError());
            isAuthorize = Boolean.parseBoolean(String.valueOf(verifyTokenResult.getData()));
        }

        String apiServiceName = CommonUtils.obtainApiServiceName(clientType);
        String data = null;
        if (isAuthorize) {
            data = CommonUtils.getOutsideUrl(apiServiceName, "proxy", "doGetPermit") + "/" + partitionCode + "/" + Constants.SERVICE_NAME_CATERING + "/eleme/bindingStore?tenantId=" + tenantId + "&branchId=" + branchId + "&userId=" + userId;
        } else {
            String elemeUrl = ConfigurationUtils.getConfiguration(Constants.ELEME_SERVICE_URL);
            String elemeAppKey = ConfigurationUtils.getConfiguration(Constants.ELEME_APP_KEY);

            String redirectUri = URLEncoder.encode(CommonUtils.getOutsideUrl(apiServiceName, "proxy", "doGetPermit") + "/" + partitionCode + "/" + Constants.SERVICE_NAME_CATERING + "/eleme/tenantAuthorizeCallback", Constants.CHARSET_NAME_UTF_8);
            String state = tenantId + "Z" + branchId + "Z" + userId + "Z" + elemeAccountType;
            data = String.format(Constants.ELEME_TENANT_AUTHORIZE_URL_FORMAT, elemeUrl + "/" + "authorize", "code", elemeAppKey, redirectUri, state, "all");
        }
        return ApiRest.builder().data(data).message("生成授权链接成功！").successful(true).build();
    }

    /**
     * 处理商户授权回调
     *
     * @param tenantId
     * @param branchId
     * @param userId
     * @param elemeAccountType
     */
    public void handleTenantAuthorizeCallback(BigInteger tenantId, BigInteger branchId, BigInteger userId, int elemeAccountType, String code) {
        Map<String, String> requestParameters = new HashMap<String, String>();
        requestParameters.put("tenantId", tenantId.toString());
        requestParameters.put("branchId", branchId.toString());
        requestParameters.put("userId", userId.toString());
        requestParameters.put("elemeAccountType", String.valueOf(elemeAccountType));
        requestParameters.put("code", code);
        ApiRest apiRest = ProxyUtils.doPostWithRequestParameters(Constants.SERVICE_NAME_PLATFORM, "eleme", "handleTenantAuthorizeCallback", requestParameters);
        ValidateUtils.isTrue(apiRest.isSuccessful(), apiRest.getError());
    }

    /**
     * 保存饿了么订单
     *
     * @param elemeCallbackMessage
     * @param uuid
     * @throws IOException
     */
    @Transactional(rollbackFor = Exception.class)
    public void saveElemeOrder(ElemeCallbackMessage elemeCallbackMessage, String uuid) throws ParseException {
        JSONObject messageJsonObject = JSONObject.fromObject(elemeCallbackMessage.getMessage());

        String openId = messageJsonObject.getString("openId");
        String[] array = openId.split("Z");
        BigInteger tenantId = NumberUtils.createBigInteger(array[0]);
        BigInteger branchId = NumberUtils.createBigInteger(array[1]);

        SearchModel branchSearchModel = new SearchModel(true);
        branchSearchModel.addSearchCondition("tenant_id", Constants.SQL_OPERATION_SYMBOL_EQUAL, tenantId);
        branchSearchModel.addSearchCondition("id", Constants.SQL_OPERATION_SYMBOL_EQUAL, branchId);
        branchSearchModel.addSearchCondition("shop_id", Constants.SQL_OPERATION_SYMBOL_EQUAL, elemeCallbackMessage.getShopId());
        Branch branch = DatabaseHelper.find(Branch.class, branchSearchModel);
        ValidateUtils.notNull(branch, "门店不存在！");

        String tenantCode = branch.getTenantCode();

        // 开始保存饿了么订单
        JSONArray phoneList = messageJsonObject.optJSONArray("phoneList");

        BigInteger userId = CommonUtils.getServiceSystemUserId();
        SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
        int orderType = DietOrderConstants.ORDER_TYPE_ELEME_ORDER;
        int orderStatus = Constants.INT_DEFAULT_VALUE;
        String elemeOrderStatus = messageJsonObject.getString("status");
        if (DietOrderConstants.PENDING.equals(elemeOrderStatus)) {
            orderStatus = DietOrderConstants.ORDER_STATUS_PENDING;
        } else if (DietOrderConstants.UNPROCESSED.equals(elemeOrderStatus)) {
            orderStatus = DietOrderConstants.ORDER_STATUS_UNPROCESSED;
        } else if (DietOrderConstants.REFUNDING.equals(elemeOrderStatus)) {
            orderStatus = DietOrderConstants.ORDER_STATUS_REFUNDING;
        } else if (DietOrderConstants.VALID.equals(elemeOrderStatus)) {
            orderStatus = DietOrderConstants.ORDER_STATUS_VALID;
        } else if (DietOrderConstants.INVALID.equals(elemeOrderStatus)) {
            orderStatus = DietOrderConstants.ORDER_STATUS_INVALID;
        } else if (DietOrderConstants.SETTLED.equals(elemeOrderStatus)) {
            orderStatus = DietOrderConstants.ORDER_STATUS_INVALID;
        }
        int payStatus = 0;
        int paidType = 0;
        BigDecimal totalAmount = BigDecimal.valueOf(messageJsonObject.getDouble("originalPrice"));
        BigDecimal discountAmount = BigDecimal.valueOf(messageJsonObject.getDouble("shopPart")).abs();
        BigDecimal payableAmount = totalAmount.subtract(discountAmount);
        BigDecimal paidAmount = BigDecimal.ZERO;
        boolean onlinePaid = messageJsonObject.getBoolean("onlinePaid");
        if (onlinePaid) {
            payStatus = DietOrderConstants.PAY_STATUS_PAID;
            paidType = Constants.PAID_TYPE_ELM;
            paidAmount = payableAmount;
        } else {
            payStatus = DietOrderConstants.PAY_STATUS_UNPAID;
        }
        int refundStatus = 0;
        String elemeRefundStatus = messageJsonObject.getString("refundStatus");
        if (DietOrderConstants.NO_REFUND.equals(elemeRefundStatus)) {
            refundStatus = DietOrderConstants.REFUND_STATUS_NO_REFUND;
        } else if (DietOrderConstants.APPLIED.equals(elemeOrderStatus)) {
            refundStatus = DietOrderConstants.REFUND_STATUS_APPLIED;
        } else if (DietOrderConstants.REJECTED.equals(elemeOrderStatus)) {
            refundStatus = DietOrderConstants.REFUND_STATUS_REJECTED;
        } else if (DietOrderConstants.ARBITRATING.equals(elemeOrderStatus)) {
            refundStatus = DietOrderConstants.REFUND_STATUS_ARBITRATING;
        } else if (DietOrderConstants.FAILED.equals(elemeOrderStatus)) {
            refundStatus = DietOrderConstants.REFUND_STATUS_FAILED;
        } else if (DietOrderConstants.SUCCESSFUL.equals(elemeOrderStatus)) {
            refundStatus = DietOrderConstants.REFUND_STATUS_SUCCESSFUL;
        }

        String description = messageJsonObject.getString("description");
        String deliveryGeo = messageJsonObject.getString("deliveryGeo");
        String[] geolocation = deliveryGeo.split(",");
        String deliveryLongitude = geolocation[0];
        String deliveryLatitude = geolocation[1];
        Date deliverTime = Constants.DATETIME_DEFAULT_VALUE;
        Object deliverTimeObject = messageJsonObject.opt("deliverTime");
        if (deliverTimeObject != null && !(deliverTimeObject instanceof JSONNull)) {
            deliverTime = simpleDateFormat.parse(deliverTimeObject.toString());
        }
        Date activeTime = simpleDateFormat.parse(messageJsonObject.getString("activeAt"));
        boolean invoiced = messageJsonObject.getBoolean("invoiced");
        String invoiceType = Constants.VARCHAR_DEFAULT_VALUE;
        String invoice = Constants.VARCHAR_DEFAULT_VALUE;
        if (invoiced) {
            invoiceType = messageJsonObject.getString("invoiceType");
            invoice = messageJsonObject.getString("invoice");
        }
        BigDecimal deliverFee = BigDecimal.valueOf(messageJsonObject.getDouble("deliverFee"));

        DietOrder dietOrder = DietOrder.builder()
                .tenantId(tenantId)
                .tenantCode(tenantCode)
                .branchId(branchId)
                .orderNumber("E" + messageJsonObject.get("id"))
                .orderType(orderType)
                .orderStatus(orderStatus)
                .payStatus(payStatus)
                .refundStatus(refundStatus)
                .totalAmount(totalAmount)
                .discountAmount(discountAmount)
                .payableAmount(payableAmount)
                .paidAmount(paidAmount)
                .paidType(paidType)
                .remark(StringUtils.isNotBlank(description) ? description : Constants.VARCHAR_DEFAULT_VALUE)
                .deliveryAddress(messageJsonObject.getString("address"))
                .deliveryLongitude(deliveryLongitude)
                .deliveryLatitude(deliveryLatitude)
                .deliverTime(deliverTime)
                .activeTime(activeTime)
                .deliverFee(deliverFee)
                .telephoneNumber(StringUtils.join(phoneList, ","))
                .daySerialNumber(messageJsonObject.getString("daySn"))
                .consignee(messageJsonObject.getString("consignee"))
                .invoiced(invoiced)
                .invoiceType(invoiceType)
                .invoice(invoice)
                .vipId(BigInteger.ZERO)
                .createdUserId(userId)
                .updatedUserId(userId)
                .build();
        DatabaseHelper.insert(dietOrder);
        BigInteger dietOrderId = dietOrder.getId();

        JSONArray orderActivitiesJsonArray = messageJsonObject.optJSONArray("orderActivities");
        if (orderActivitiesJsonArray != null) {
            int orderActivitiesSize = orderActivitiesJsonArray.size();
            for (int orderActivitiesIndex = 0; orderActivitiesIndex < orderActivitiesSize; orderActivitiesIndex++) {
                JSONObject elemeActivityJsonObject = orderActivitiesJsonArray.optJSONObject(orderActivitiesIndex);
                int categoryId = elemeActivityJsonObject.getInt("categoryId");
                DietOrderActivity dietOrderActivity = DietOrderActivity.builder()
                        .tenantId(tenantId)
                        .tenantCode(tenantCode)
                        .branchId(branchId)
                        .dietOrderId(dietOrderId)
                        .activityId(BigInteger.valueOf(elemeActivityJsonObject.getLong("id")))
                        .activityName(elemeActivityJsonObject.getString("name"))
                        .activityType(categoryId)
                        .amount(BigDecimal.valueOf(elemeActivityJsonObject.getDouble("restaurantPart")).abs())
                        .createdUserId(userId)
                        .updatedUserId(userId)
                        .build();
                DatabaseHelper.insert(dietOrderActivity);
            }
        }

        if (onlinePaid) {
            DietOrderPayment dietOrderPayment = DietOrderPayment.builder()
                    .tenantId(tenantId)
                    .tenantCode(tenantCode)
                    .branchId(branchId)
                    .dietOrderId(dietOrderId)
                    .paymentId(Constants.ELM_PAYMENT_ID)
                    .paymentCode(Constants.ELM_PAYMENT_CODE)
                    .paymentName(Constants.ELM_PAYMENT_NAME)
                    .occurrenceTime(activeTime)
                    .createdUserId(userId)
                    .updatedUserId(userId)
                    .paidAmount(paidAmount)
                    .build();
            DatabaseHelper.insert(dietOrderPayment);
        }

        JSONArray groupsJsonArray = messageJsonObject.optJSONArray("groups");
        DietOrderGroup extraDietOrderGroup = null;
        int groupsSize = groupsJsonArray.size();
        BigInteger packageFeeItemId = BigInteger.valueOf(-70000);
        BigInteger packageFeeSkuId = Constants.BIG_INTEGER_MINUS_ONE;
        for (int groupsIndex = 0; groupsIndex < groupsSize; groupsIndex++) {
            JSONObject elemeGroupJsonObject = groupsJsonArray.getJSONObject(groupsIndex);
            String name = elemeGroupJsonObject.getString("name");
            String type = elemeGroupJsonObject.getString("type");
            DietOrderGroup dietOrderGroup = DietOrderGroup.builder()
                    .tenantId(tenantId)
                    .tenantCode(tenantCode)
                    .branchId(branchId)
                    .dietOrderId(dietOrderId)
                    .name(name)
                    .type(type)
                    .createdUserId(userId)
                    .updatedUserId(userId)
                    .build();
            DatabaseHelper.insert(dietOrderGroup);

            BigInteger dietOrderGroupId = dietOrderGroup.getId();

            JSONArray itemsJsonArray = elemeGroupJsonObject.optJSONArray("items");
            int itemsSize = itemsJsonArray.size();
            for (int itemsIndex = 0; itemsIndex < itemsSize; itemsIndex++) {
                JSONObject elemeOrderItemJsonObject = itemsJsonArray.optJSONObject(itemsIndex);
                JSONArray newSpecsJsonArray = elemeOrderItemJsonObject.optJSONArray("newSpecs");
                String goodsSpecificationName = "";
                BigInteger categoryId = Constants.ELEME_GOODS_CATEGORY_ID;
                String categoryName = Constants.ELEME_GOODS_CATEGORY_NAME;
                if (CollectionUtils.isNotEmpty(newSpecsJsonArray)) {
                    goodsSpecificationName = newSpecsJsonArray.getJSONObject(0).getString("value");
                }

                BigDecimal total = BigDecimal.valueOf(elemeOrderItemJsonObject.getDouble("total"));

                DietOrderDetail.Builder dietOrderDetailBuilder = DietOrderDetail.builder()
                        .tenantId(tenantId)
                        .tenantCode(tenantCode)
                        .branchId(branchId)
                        .dietOrderId(dietOrderId)
                        .dietOrderGroupId(dietOrderGroupId)
                        .goodsName(elemeOrderItemJsonObject.getString("name"))
                        .goodsSpecificationName(goodsSpecificationName)
                        .price(BigDecimal.valueOf(elemeOrderItemJsonObject.getDouble("price")))
                        .attributeIncrease(BigDecimal.ZERO)
                        .quantity(BigDecimal.valueOf(elemeOrderItemJsonObject.getDouble("quantity")))
                        .totalAmount(total)
                        .discountAmount(BigDecimal.ZERO)
                        .payableAmount(total)
                        .createdUserId(userId)
                        .updatedUserId(userId);

                BigInteger id = BigInteger.valueOf(elemeOrderItemJsonObject.getLong("id"));
                BigInteger skuId = BigInteger.valueOf(elemeOrderItemJsonObject.getLong("skuId"));
                boolean isPackageFee = packageFeeItemId.compareTo(id) == 0 && packageFeeSkuId.compareTo(skuId) == 0;

                DietOrderDetail dietOrderDetail = null;
                if (isPackageFee) {
                    dietOrderDetail = dietOrderDetailBuilder.goodsType(Constants.GOODS_TYPE_PACKAGE_FEE)
                            .goodsId(Constants.BIG_INTEGER_MINUS_TWO)
                            .goodsSpecificationId(Constants.BIG_INTEGER_MINUS_TWO)
                            .categoryId(Constants.FICTITIOUS_GOODS_CATEGORY_ID)
                            .categoryName(Constants.FICTITIOUS_GOODS_CATEGORY_NAME)
                            .build();
                } else {
                    dietOrderDetail = dietOrderDetailBuilder.goodsType(Constants.GOODS_TYPE_ORDINARY_GOODS)
                            .goodsId(id)
                            .goodsSpecificationId(skuId)
                            .categoryId(categoryId)
                            .categoryName(categoryName)
                            .build();
                }
                DatabaseHelper.insert(dietOrderDetail);

                BigInteger dietOrderDetailId = dietOrderDetail.getId();
                JSONArray attributesJsonArray = elemeOrderItemJsonObject.optJSONArray("attributes");
                if (CollectionUtils.isNotEmpty(attributesJsonArray)) {
                    int attributesSize = attributesJsonArray.size();
                    for (int attributesIndex = 0; attributesIndex < attributesSize; attributesIndex++) {
                        JSONObject attributeJsonObject = attributesJsonArray.optJSONObject(attributesIndex);
                        DietOrderDetailGoodsAttribute dietOrderDetailGoodsAttribute = DietOrderDetailGoodsAttribute.builder()
                                .tenantId(tenantId)
                                .tenantCode(tenantCode)
                                .branchId(branchId)
                                .dietOrderId(dietOrderId)
                                .dietOrderGroupId(dietOrderGroupId)
                                .dietOrderDetailId(dietOrderDetailId)
                                .goodsAttributeGroupId(BigInteger.ZERO)
                                .goodsAttributeGroupName(attributeJsonObject.getString("name"))
                                .goodsAttributeId(BigInteger.ZERO)
                                .goodsAttributeName(attributeJsonObject.getString("value"))
                                .price(BigDecimal.ZERO)
                                .createdUserId(userId)
                                .updatedUserId(userId)
                                .build();
                        DatabaseHelper.insert(dietOrderDetailGoodsAttribute);
                    }
                }
            }

            if (DietOrderConstants.GROUP_TYPE_EXTRA.equals(type)) {
                extraDietOrderGroup = dietOrderGroup;
            }
        }

        if (deliverFee.compareTo(BigDecimal.ZERO) > 0) {
            if (extraDietOrderGroup == null) {
                extraDietOrderGroup = DietOrderGroup.builder()
                        .tenantId(tenantId)
                        .tenantCode(tenantCode)
                        .branchId(branchId)
                        .dietOrderId(dietOrderId)
                        .name("其他费用")
                        .type(DietOrderConstants.GROUP_TYPE_EXTRA)
                        .createdUserId(userId)
                        .updatedUserId(userId)
                        .build();
                DatabaseHelper.insert(extraDietOrderGroup);
            }
            DietOrderDetail dietOrderDetail = DietOrderDetail.builder()
                    .tenantId(tenantId)
                    .tenantCode(tenantCode)
                    .branchId(branchId)
                    .dietOrderId(dietOrderId)
                    .dietOrderGroupId(extraDietOrderGroup.getId())
                    .goodsType(Constants.GOODS_TYPE_DELIVER_FEE)
                    .goodsId(Constants.BIG_INTEGER_MINUS_ONE)
                    .goodsName("配送费")
                    .goodsSpecificationId(Constants.BIG_INTEGER_MINUS_ONE)
                    .goodsSpecificationName(Constants.VARCHAR_DEFAULT_VALUE)
                    .categoryId(Constants.FICTITIOUS_GOODS_CATEGORY_ID)
                    .categoryName(Constants.FICTITIOUS_GOODS_CATEGORY_NAME)
                    .price(deliverFee)
                    .attributeIncrease(Constants.DECIMAL_DEFAULT_VALUE)
                    .quantity(BigDecimal.ONE)
                    .totalAmount(deliverFee)
                    .discountAmount(BigDecimal.ZERO)
                    .payableAmount(deliverFee)
                    .createdUserId(userId)
                    .updatedUserId(userId)
                    .build();
            DatabaseHelper.insert(dietOrderDetail);
        }
        elemeCallbackMessage.setBranchId(branchId);
        elemeCallbackMessage.setCreatedUserId(userId);
        elemeCallbackMessage.setUpdatedUserId(userId);
        DatabaseHelper.insert(elemeCallbackMessage);
    }

    /**
     * 处理饿了么退单消息
     *
     * @param elemeCallbackMessage
     * @param uuid
     * @throws IOException
     */
    @Transactional(rollbackFor = Exception.class)
    public void handleElemeRefundOrderMessage(ElemeCallbackMessage elemeCallbackMessage, String uuid) {

    }

    /**
     * 处理饿了么催单消息
     *
     * @param elemeCallbackMessage
     * @param uuid
     * @throws IOException
     */
    @Transactional(rollbackFor = Exception.class)
    public void handleElemeReminderMessage(ElemeCallbackMessage elemeCallbackMessage, String uuid) {

    }

    /**
     * 处理饿了么取消单消息
     *
     * @param elemeCallbackMessage
     * @param uuid
     * @throws IOException
     */
    @Transactional(rollbackFor = Exception.class)
    public void handleElemeCancelOrderMessage(ElemeCallbackMessage elemeCallbackMessage, String uuid) {

    }

    /**
     * 处理订单状态变更消息
     *
     * @param elemeCallbackMessage
     * @param uuid
     * @throws IOException
     */
    @Transactional(rollbackFor = Exception.class)
    public void handleElemeOrderStateChangeMessage(ElemeCallbackMessage elemeCallbackMessage, String uuid) {

    }

    /**
     * 处理运单状态变更消息
     *
     * @param elemeCallbackMessage
     * @param uuid
     * @throws IOException
     */
    @Transactional(rollbackFor = Exception.class)
    public void handleElemeDeliveryOrderStateChangeMessage(ElemeCallbackMessage elemeCallbackMessage, String uuid) {

    }

    public void handleElemeShopStateChangeMessage(ElemeCallbackMessage elemeCallbackMessage, String uuid) {

    }

    @Transactional(rollbackFor = Exception.class)
    public void handleAuthorizationStateChangeMessage(ElemeCallbackMessage elemeCallbackMessage, String uuid) {
        BigInteger tenantId = elemeCallbackMessage.getTenantId();
        BigInteger shopId = elemeCallbackMessage.getShopId();

        SearchModel searchModel = new SearchModel(true);
        searchModel.addSearchCondition(Branch.ColumnName.TENANT_ID, Constants.SQL_OPERATION_SYMBOL_EQUAL, tenantId);
        searchModel.addSearchCondition(Branch.ColumnName.SHOP_ID, Constants.SQL_OPERATION_SYMBOL_EQUAL, shopId);
        Branch branch = DatabaseHelper.find(Branch.class, searchModel);

        branch.setShopId(BigInteger.ZERO);
        DatabaseHelper.update(branch);

        BigInteger userId = CommonUtils.getServiceSystemUserId();
        elemeCallbackMessage.setCreatedUserId(userId);
        elemeCallbackMessage.setUpdatedUserId(userId);
        elemeCallbackMessage.setBranchId(branch.getId());
        DatabaseHelper.insert(elemeCallbackMessage);
    }

    /**
     * 查询门店信息，并校验非空
     *
     * @param tenantId
     * @param branchId
     * @return
     */
    private Branch findBranch(BigInteger tenantId, BigInteger branchId) {
        SearchModel searchModel = new SearchModel(true);
        searchModel.addSearchCondition(Branch.ColumnName.TENANT_ID, Constants.SQL_OPERATION_SYMBOL_EQUAL, tenantId);
        searchModel.addSearchCondition(Branch.ColumnName.ID, Constants.SQL_OPERATION_SYMBOL_EQUAL, branchId);
        Branch branch = DatabaseHelper.find(Branch.class, searchModel);
        ValidateUtils.notNull(branch, "门店不存在！");
        return branch;
    }

    /**
     * 查询订单信息，并校验非空
     *
     * @param tenantId
     * @param branchId
     * @param elemeOrderId
     * @return
     */
    private DietOrder findElemeOrder(BigInteger tenantId, BigInteger branchId, BigInteger elemeOrderId) {
        SearchModel searchModel = new SearchModel();
        searchModel.addSearchCondition(DietOrder.ColumnName.TENANT_ID, Constants.SQL_OPERATION_SYMBOL_EQUAL, tenantId);
        searchModel.addSearchCondition(DietOrder.ColumnName.BRANCH_ID, Constants.SQL_OPERATION_SYMBOL_EQUAL, branchId);
        searchModel.addSearchCondition(DietOrder.ColumnName.ID, Constants.SQL_OPERATION_SYMBOL_EQUAL, elemeOrderId);
        DietOrder dietOrder = DatabaseHelper.find(DietOrder.class, searchModel);
        ValidateUtils.notNull(dietOrder, "订单不存在！");
        return dietOrder;
    }

    /**
     * 批量查询订单，并校验非空
     *
     * @param tenantId
     * @param branchId
     * @param elemeOrderIds
     * @return
     */
    public List<DietOrder> findAllElemeOrders(BigInteger tenantId, BigInteger branchId, List<BigInteger> elemeOrderIds) {
        SearchModel searchModel = new SearchModel(true);
        searchModel.addSearchCondition(DietOrder.ColumnName.TENANT_ID, Constants.SQL_OPERATION_SYMBOL_EQUAL, tenantId);
        searchModel.addSearchCondition(DietOrder.ColumnName.BRANCH_ID, Constants.SQL_OPERATION_SYMBOL_EQUAL, branchId);
        searchModel.addSearchCondition(DietOrder.ColumnName.ID, Constants.SQL_OPERATION_SYMBOL_IN, elemeOrderIds);
        List<DietOrder> dietOrders = DatabaseHelper.findAll(DietOrder.class, searchModel);
        ValidateUtils.notEmpty(dietOrders, "订单不存在！");
        return dietOrders;
    }

    /**
     * 获取订单ID
     *
     * @param dietOrders
     * @return
     */
    public List<String> obtainOrderIds(List<DietOrder> dietOrders) {
        List<String> orderIds = new ArrayList<String>();
        for (DietOrder dietOrder : dietOrders) {
            orderIds.add(dietOrder.getOrderNumber().substring(1));
        }
        return orderIds;
    }

    @Transactional(readOnly = true)
    public ApiRest obtainElemeCallbackMessage(ObtainElemeCallbackMessageModel obtainElemeCallbackMessageModel) {
        BigInteger tenantId = obtainElemeCallbackMessageModel.obtainTenantId();
        BigInteger branchId = obtainElemeCallbackMessageModel.obtainBranchId();
        BigInteger elemeCallbackMessageId = obtainElemeCallbackMessageModel.getElemeCallbackMessageId();

        SearchModel searchModel = new SearchModel(false);
        searchModel.addSearchCondition("tenant_id", Constants.SQL_OPERATION_SYMBOL_EQUAL, tenantId);
        searchModel.addSearchCondition("branch_id", Constants.SQL_OPERATION_SYMBOL_EQUAL, branchId);
        searchModel.addSearchCondition("id", Constants.SQL_OPERATION_SYMBOL_EQUAL, elemeCallbackMessageId);
        ElemeCallbackMessage elemeCallbackMessage = DatabaseHelper.find(ElemeCallbackMessage.class, searchModel);

        Map<String, Object> data = new HashMap<String, Object>();
        data.put("id", elemeCallbackMessage.getId());
        data.put("orderId", elemeCallbackMessage.getOrderId());
        data.put("requestId", elemeCallbackMessage.getRequestId());
        data.put("type", elemeCallbackMessage.getType());
        data.put("appId", elemeCallbackMessage.getAppId());
        data.put("message", JSONObject.fromObject(elemeCallbackMessage.getMessage()));
        data.put("shopId", elemeCallbackMessage.getShopId());
        data.put("timestamp", elemeCallbackMessage.getTimestamp());
        data.put("signature", elemeCallbackMessage.getSignature());
        data.put("userId", elemeCallbackMessage.getUserId());

        return ApiRest.builder().data(data).message("获取饿了么回调消息成功！").successful(true).build();
    }

    @Transactional(readOnly = true)
    public ApiRest obtainElemeOrder(ObtainElemeOrderModel obtainElemeOrderModel) {
        return null;
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiRest doBindingStore(DoBindingStoreModel doBindingStoreModel) {
        BigInteger tenantId = doBindingStoreModel.getTenantId();
        BigInteger branchId = doBindingStoreModel.getBranchId();
        BigInteger shopId = doBindingStoreModel.getShopId();
        BigInteger userId = doBindingStoreModel.getUserId();

        String updatedRemark = "门店(" + branchId + ")绑定饿了么(" + shopId + ")，清除绑定关系！";
        UpdateModel updateModel = new UpdateModel(true);
        updateModel.setTableName(Branch.TABLE_NAME);
        updateModel.addContentValue(Branch.ColumnName.SHOP_ID, BigInteger.ZERO);
        updateModel.addContentValue(Branch.ColumnName.UPDATED_USER_ID, userId);
        updateModel.addContentValue(Branch.ColumnName.UPDATED_REMARK, updatedRemark);
        updateModel.addSearchCondition(Branch.ColumnName.SHOP_ID, Constants.SQL_OPERATION_SYMBOL_EQUAL, shopId);
        DatabaseHelper.universalUpdate(updateModel);

        SearchModel searchModel = new SearchModel(true);
        searchModel.addSearchCondition(Branch.ColumnName.ID, Constants.SQL_OPERATION_SYMBOL_EQUAL, branchId);
        searchModel.addSearchCondition(Branch.ColumnName.TENANT_ID, Constants.SQL_OPERATION_SYMBOL_EQUAL, tenantId);
        Branch branch = DatabaseHelper.find(Branch.class, searchModel);
        ValidateUtils.notNull(branch, "门店不存在！");
        branch.setShopId(shopId);
        branch.setUpdatedUserId(userId);
        branch.setUpdatedRemark("绑定饿了么，设置shop_id！");
        DatabaseHelper.update(branch);

        Map<String, String> saveElemeBranchMappingRequestParameters = new HashMap<String, String>();
        saveElemeBranchMappingRequestParameters.put("tenantId", tenantId.toString());
        saveElemeBranchMappingRequestParameters.put("branchId", branchId.toString());
        saveElemeBranchMappingRequestParameters.put("shopId", shopId.toString());
        saveElemeBranchMappingRequestParameters.put("userId", userId.toString());

        ApiRest saveElemeBranchMappingApiRest = ProxyUtils.doPostWithRequestParameters(Constants.SERVICE_NAME_PLATFORM, "eleme", "saveElemeBranchMapping", saveElemeBranchMappingRequestParameters);
        ValidateUtils.isTrue(saveElemeBranchMappingApiRest.isSuccessful(), saveElemeBranchMappingApiRest.getError());

        return ApiRest.builder().message("饿了么门店绑定成功！").successful(true).build();
    }

    @Transactional(readOnly = true)
    public void pushElemeMessage(BigInteger tenantId, BigInteger branchId, BigInteger elemeOrderId, Integer type, String uuid, final int count, int interval) {
        SearchModel searchModel = new SearchModel(true);
        searchModel.addSearchCondition("tenant_id", Constants.SQL_OPERATION_SYMBOL_EQUAL, tenantId);
        searchModel.addSearchCondition("branch_id", Constants.SQL_OPERATION_SYMBOL_EQUAL, branchId);
        searchModel.addSearchCondition("online", Constants.SQL_OPERATION_SYMBOL_EQUAL, 1);
        List<Pos> poses = DatabaseHelper.findAll(Pos.class, searchModel);
        if (CollectionUtils.isNotEmpty(poses)) {
            List<String> deviceIds = new ArrayList<String>();
            for (Pos pos : poses) {
                deviceIds.add(pos.getDeviceId());
            }
            PushModel pushModel = new PushModel();
            PushMessageThread pushMessageThread = new PushMessageThread(pushModel, uuid, count, interval);
            new Thread(pushMessageThread).start();
        }
    }

    /**
     * 获取订单
     *
     * @param getOrderModel
     * @return
     */
    @Transactional(readOnly = true)
    public ApiRest getOrder(GetOrderModel getOrderModel) throws IOException {
        BigInteger tenantId = getOrderModel.obtainTenantId();
        BigInteger branchId = getOrderModel.obtainBranchId();
        BigInteger elemeOrderId = getOrderModel.getElemeOrderId();


        Branch branch = findBranch(tenantId, branchId);
        DietOrder dietOrder = findElemeOrder(tenantId, branchId, elemeOrderId);
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("orderId", dietOrder.getOrderNumber().substring(1));

        Map<String, Object> result = ElemeUtils.callElemeSystem(tenantId.toString(), branchId.toString(), branch.getElemeAccountType(), "eleme.order.getOrder", params);

        return ApiRest.builder().data(result).message("获取订单成功！").successful(true).build();
    }

    /**
     * 批量查询订单
     *
     * @param batchGetOrdersModel
     * @return
     * @throws IOException
     */
    @Transactional(readOnly = true)
    public ApiRest batchGetOrders(BatchGetOrdersModel batchGetOrdersModel) throws IOException {
        BigInteger tenantId = batchGetOrdersModel.obtainTenantId();
        BigInteger branchId = batchGetOrdersModel.obtainBranchId();
        List<BigInteger> elemeOrderIds = batchGetOrdersModel.getElemeOrderIds();

        Branch branch = findBranch(tenantId, branchId);
        List<DietOrder> dietOrders = findAllElemeOrders(tenantId, branchId, elemeOrderIds);
        List<String> orderIds = obtainOrderIds(dietOrders);

        Map<String, Object> params = new HashMap<String, Object>();
        params.put("orderIds", orderIds);

        Map<String, Object> result = ElemeUtils.callElemeSystem(tenantId.toString(), branchId.toString(), branch.getElemeAccountType(), "eleme.order.mgetOrders", params);

        return ApiRest.builder().data(result).message("批量查询订单成功！").successful(true).build();
    }

    /**
     * 确认订单
     *
     * @param confirmOrderLiteModel
     * @return
     * @throws IOException
     */
    @Transactional(readOnly = true)
    public ApiRest confirmOrderLite(ConfirmOrderLiteModel confirmOrderLiteModel) throws IOException {
        BigInteger tenantId = confirmOrderLiteModel.obtainTenantId();
        BigInteger branchId = confirmOrderLiteModel.obtainBranchId();
        BigInteger elemeOrderId = confirmOrderLiteModel.getElemeOrderId();

        Branch branch = findBranch(tenantId, branchId);
        DietOrder dietOrder = findElemeOrder(tenantId, branchId, elemeOrderId);

        Map<String, Object> params = new HashMap<String, Object>();
        params.put("orderId", dietOrder.getOrderNumber().substring(1));
        Map<String, Object> result = ElemeUtils.callElemeSystem(tenantId.toString(), branchId.toString(), branch.getElemeAccountType(), "eleme.order.confirmOrderLite", params);

        return ApiRest.builder().data(result).message("确认订单成功！").successful(true).build();
    }

    /**
     * 取消订单
     *
     * @param cancelOrderLiteModel
     * @return
     * @throws IOException
     */
    @Transactional(readOnly = true)
    public ApiRest cancelOrderLite(CancelOrderLiteModel cancelOrderLiteModel) throws IOException {
        BigInteger tenantId = cancelOrderLiteModel.obtainTenantId();
        BigInteger branchId = cancelOrderLiteModel.obtainBranchId();
        BigInteger elemeOrderId = cancelOrderLiteModel.getElemeOrderId();

        Branch branch = findBranch(tenantId, branchId);
        DietOrder dietOrder = findElemeOrder(tenantId, branchId, elemeOrderId);

        Map<String, Object> params = new HashMap<String, Object>();
        params.put("orderId", dietOrder.getOrderNumber().substring(1));
        params.put("type", cancelOrderLiteModel.getType());
        ApplicationHandler.ifNotNullPut(params, "remark", cancelOrderLiteModel.getRemark());

        Map<String, Object> result = ElemeUtils.callElemeSystem(tenantId.toString(), branchId.toString(), branch.getElemeAccountType(), "eleme.order.cancelOrderLite", params);

        return ApiRest.builder().data(result).message("取消订单成功！").successful(true).build();
    }

    /**
     * 同意退单/同意取消单
     *
     * @param agreeRefundLiteModel
     * @return
     * @throws IOException
     */
    @Transactional(readOnly = true)
    public ApiRest agreeRefundLite(AgreeRefundLiteModel agreeRefundLiteModel) throws IOException {
        BigInteger tenantId = agreeRefundLiteModel.obtainTenantId();
        BigInteger branchId = agreeRefundLiteModel.obtainBranchId();
        BigInteger elemeOrderId = agreeRefundLiteModel.getElemeOrderId();

        Branch branch = findBranch(tenantId, branchId);
        DietOrder dietOrder = findElemeOrder(tenantId, branchId, elemeOrderId);

        Map<String, Object> params = new HashMap<String, Object>();
        params.put("orderId", dietOrder.getOrderNumber().substring(1));
        Map<String, Object> result = ElemeUtils.callElemeSystem(tenantId.toString(), branchId.toString(), branch.getElemeAccountType(), "eleme.order.agreeRefundLite", params);

        return ApiRest.builder().data(result).message("同意退单/同意取消单成功！").successful(true).build();
    }

    /**
     * 不同意退单/不同意取消单
     *
     * @param disagreeRefundLiteModel
     * @return
     * @throws IOException
     */
    @Transactional(readOnly = true)
    public ApiRest disagreeRefundLite(DisagreeRefundLiteModel disagreeRefundLiteModel) throws IOException {
        BigInteger tenantId = disagreeRefundLiteModel.obtainTenantId();
        BigInteger branchId = disagreeRefundLiteModel.obtainBranchId();

        Branch branch = findBranch(tenantId, branchId);
        DietOrder dietOrder = findElemeOrder(tenantId, branchId, disagreeRefundLiteModel.getElemeOrderId());

        Map<String, Object> params = new HashMap<String, Object>();
        params.put("orderId", dietOrder.getOrderNumber().substring(1));
        Map<String, Object> result = ElemeUtils.callElemeSystem(tenantId.toString(), branchId.toString(), branch.getElemeAccountType(), "eleme.order.disagreeRefundLite", params);

        return ApiRest.builder().data(result).message("不同意退单/不同意取消单成功！").successful(true).build();
    }

    /**
     * 配送异常或者物流拒单后选择自行配送
     *
     * @param deliveryBySelfLiteModel
     * @return
     * @throws IOException
     */
    @Transactional(readOnly = true)
    public ApiRest deliveryBySelfLite(DeliveryBySelfLiteModel deliveryBySelfLiteModel) throws IOException {
        BigInteger tenantId = deliveryBySelfLiteModel.obtainTenantId();
        BigInteger branchId = deliveryBySelfLiteModel.obtainBranchId();
        BigInteger elemeOrderId = deliveryBySelfLiteModel.getElemeOrderId();

        Branch branch = findBranch(tenantId, branchId);
        DietOrder dietOrder = findElemeOrder(tenantId, branchId, elemeOrderId);

        Map<String, Object> params = new HashMap<String, Object>();
        params.put("orderId", dietOrder.getOrderNumber().substring(1));
        Map<String, Object> result = ElemeUtils.callElemeSystem(tenantId.toString(), branchId.toString(), branch.getElemeAccountType(), "eleme.order.deliveryBySelfLite", params);

        return ApiRest.builder().data(result).message("配送异常或者物流拒单后选择自行配送成功！").successful(true).build();
    }

    /**
     * 配送异常或者物流拒单后选择不再配送
     *
     * @param noMoreDeliveryLiteModel
     * @return
     * @throws IOException
     */
    @Transactional(readOnly = true)
    public ApiRest noMoreDeliveryLite(NoMoreDeliveryLiteModel noMoreDeliveryLiteModel) throws IOException {
        BigInteger tenantId = noMoreDeliveryLiteModel.obtainTenantId();
        BigInteger branchId = noMoreDeliveryLiteModel.obtainBranchId();

        Branch branch = findBranch(tenantId, branchId);
        DietOrder dietOrder = findElemeOrder(tenantId, branchId, noMoreDeliveryLiteModel.getElemeOrderId());

        Map<String, Object> params = new HashMap<String, Object>();
        params.put("orderId", dietOrder.getOrderNumber().substring(1));
        Map<String, Object> result = ElemeUtils.callElemeSystem(tenantId.toString(), branchId.toString(), branch.getElemeAccountType(), "eleme.order.noMoreDeliveryLite", params);

        return ApiRest.builder().data(result).message("配送异常或者物流拒单后选择不再配送成功！").build();
    }

    /**
     * 订单确认送达
     *
     * @param receivedOrderLiteModel
     * @return
     * @throws IOException
     */
    @Transactional(readOnly = true)
    public ApiRest receivedOrderLite(ReceivedOrderLiteModel receivedOrderLiteModel) throws IOException {
        BigInteger tenantId = receivedOrderLiteModel.obtainTenantId();
        BigInteger branchId = receivedOrderLiteModel.obtainBranchId();

        Branch branch = findBranch(tenantId, branchId);
        DietOrder dietOrder = findElemeOrder(tenantId, branchId, receivedOrderLiteModel.getElemeOrderId());

        Map<String, Object> params = new HashMap<String, Object>();
        params.put("orderId", dietOrder.getOrderNumber().substring(1));
        Map<String, Object> result = ElemeUtils.callElemeSystem(tenantId.toString(), branchId.toString(), branch.getElemeAccountType(), "eleme.order.receivedOrderLite", params);

        return ApiRest.builder().data(result).message("订单确认送达成功！").build();
    }

    /**
     * 回复催单
     *
     * @param replyReminderModel
     * @return
     * @throws IOException
     */
    @Transactional(readOnly = true)
    public ApiRest replyReminder(ReplyReminderModel replyReminderModel) throws IOException {
        BigInteger tenantId = replyReminderModel.obtainTenantId();
        BigInteger branchId = replyReminderModel.obtainBranchId();

        Branch branch = findBranch(tenantId, branchId);
        DietOrder dietOrder = findElemeOrder(tenantId, branchId, replyReminderModel.getElemeOrderId());

        Map<String, Object> params = new HashMap<String, Object>();
        params.put("orderId", dietOrder.getOrderNumber().substring(1));
        params.put("type", replyReminderModel.getType());
        ApplicationHandler.ifNotNullPut(params, "content", replyReminderModel.getContent());
        Map<String, Object> result = ElemeUtils.callElemeSystem(tenantId.toString(), branchId.toString(), branch.getElemeAccountType(), "eleme.order.replyReminder", params);

        return ApiRest.builder().data(result).message("回复催单成功！").successful(true).build();
    }

    /**
     * 获取商户账号信息
     *
     * @param getUserModel
     * @return
     * @throws IOException
     */
    @Transactional(readOnly = true)
    public ApiRest getUser(GetUserModel getUserModel) throws IOException {
        BigInteger tenantId = getUserModel.obtainTenantId();
        BigInteger branchId = getUserModel.obtainBranchId();

        Branch branch = findBranch(tenantId, branchId);

        Map<String, Object> result = ElemeUtils.callElemeSystem(tenantId.toString(), branchId.toString(), branch.getElemeAccountType(), "eleme.user.getUser", null);

        return ApiRest.builder().data(result).message("获取商户账号信息成功！").successful(true).build();
    }

    /**
     * 查询店铺信息
     *
     * @param getShopModel
     * @return
     * @throws IOException
     */
    @Transactional(readOnly = true)
    public ApiRest getShop(GetShopModel getShopModel) throws IOException {
        BigInteger tenantId = getShopModel.obtainTenantId();
        BigInteger branchId = getShopModel.obtainBranchId();

        Branch branch = findBranch(tenantId, branchId);

        Map<String, Object> params = new HashMap<String, Object>();
        params.put("shopId", branch.getShopId());

        Map<String, Object> result = ElemeUtils.callElemeSystem(tenantId.toString(), branchId.toString(), branch.getElemeAccountType(), "eleme.shop.getShop", null);

        return ApiRest.builder().data(result).message("查询店铺信息成功！").successful(true).build();
    }
}
