package build.dream.erp.tools;

import build.dream.common.api.ApiRest;
import build.dream.common.utils.ApplicationHandler;
import build.dream.common.utils.LogUtils;
import build.dream.common.utils.ProxyUtils;
import build.dream.erp.constants.Constants;
import build.dream.erp.services.ElemeService;
import build.dream.erp.utils.ElemeUtils;
import net.sf.json.JSONObject;
import org.apache.commons.lang.ArrayUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.Validate;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ElemeConsumerThread implements Runnable {
    private static final String ELEME_CONSUMER_THREAD_SIMPLE_NAME = "ElemeConsumerThread";

    private static final int[] ELEME_ORDER_STATE_CHANGE_MESSAGE_TYPES = {12, 14, 15, 17, 18};
    private static final int[] ELEME_REFUND_ORDER_MESSAGE_TYPES = {20, 21, 22, 23, 24, 25, 26, 30, 31, 32, 33, 34, 35, 36};
    private static final int[] ELEME_REMINDER_MESSAGE_TYPES = {45, 46};
    private static final int[] ELEME_DELIVERY_ORDER_STATE_CHANGE_MESSAGE_TYPES = {51, 52, 53, 54, 55, 56, 57, 58, 59, 60, 61, 62, 63, 64, 65, 66, 67, 68, 69, 70, 71, 72, 73, 74, 75, 76};
    private static final int[] ELEME_SHOP_STATE_CHANGE_MESSAGE_TYPES = {91, 92};

    private ElemeService elemeService = ApplicationHandler.getBean(ElemeService.class);

    @Override
    public void run() {
        while (true) {
            String elemeMessage = null;
            Integer count = null;
            JSONObject callbackJsonObject = null;
            try {
                List<String> elemeMessageBody = ElemeUtils.takeElemeMessage();
                elemeMessage = elemeMessageBody.get(0);
                count = Integer.valueOf(elemeMessageBody.get(1));

                callbackJsonObject = JSONObject.fromObject(elemeMessage);

                BigInteger shopId = BigInteger.valueOf(callbackJsonObject.getLong("shopId"));
                String message = callbackJsonObject.getString("message");
                Integer type = callbackJsonObject.getInt("type");

                if (type == 10) {
                    elemeService.saveElemeOrder(shopId, message, type);
                } else if (ArrayUtils.contains(ELEME_ORDER_STATE_CHANGE_MESSAGE_TYPES, type)) {
                    elemeService.handleElemeOrderStateChangeMessage(shopId, message, type);
                } else if (ArrayUtils.contains(ELEME_REFUND_ORDER_MESSAGE_TYPES, type)) {
                    elemeService.handleElemeRefundOrderMessage(shopId, message, type);
                } else if (ArrayUtils.contains(ELEME_REMINDER_MESSAGE_TYPES, type)) {
                    elemeService.handleElemeReminderMessage(shopId, message, type);
                } else if (ArrayUtils.contains(ELEME_DELIVERY_ORDER_STATE_CHANGE_MESSAGE_TYPES, type)) {
                    elemeService.handleElemeDeliveryOrderStateChangeMessage(shopId, message, type);
                } else if (ArrayUtils.contains(ELEME_SHOP_STATE_CHANGE_MESSAGE_TYPES, type)) {
                    elemeService.handleElemeShopStateChangeMessage(shopId, message, type);
                } else if (type == 100) {
                    elemeService.handleAuthorizationStateChangeMessage(shopId, message, type);
                }
            } catch (Exception e) {
                if (StringUtils.isNotBlank(elemeMessage)) {
                    count = count - 1;
                    if (count > 0) {
                        try {
                            ElemeUtils.addElemeMessageBlockingQueue(elemeMessage, count);
                        } catch (InterruptedException e1) {
                            saveElemeCallbackMessage(callbackJsonObject);
                        }
                    } else {
                        saveElemeCallbackMessage(callbackJsonObject);
                    }
                }
            }
        }
    }

    private void saveElemeCallbackMessage(JSONObject callbackJsonObject) {
        try {
            Map<String, String> saveElemeCallbackMessageRequestParameters = new HashMap<String, String>();
            saveElemeCallbackMessageRequestParameters.put("requestId", callbackJsonObject.getString("requestId"));
            saveElemeCallbackMessageRequestParameters.put("type", callbackJsonObject.getString("type"));
            saveElemeCallbackMessageRequestParameters.put("appId", callbackJsonObject.getString("appId"));
            saveElemeCallbackMessageRequestParameters.put("message", callbackJsonObject.getString("message"));
            saveElemeCallbackMessageRequestParameters.put("shopId", callbackJsonObject.getString("shopId"));
            saveElemeCallbackMessageRequestParameters.put("timestamp", callbackJsonObject.getString("timestamp"));
            saveElemeCallbackMessageRequestParameters.put("signature", callbackJsonObject.getString("signature"));
            saveElemeCallbackMessageRequestParameters.put("userId", callbackJsonObject.getString("userId"));

            ApiRest saveElemeCallbackMessageApiRest = ProxyUtils.doPostWithRequestParameters(Constants.SERVICE_NAME_PLATFORM, "elemeCallbackMessage", "saveElemeCallbackMessage", saveElemeCallbackMessageRequestParameters);
            Validate.isTrue(saveElemeCallbackMessageApiRest.isSuccessful(), saveElemeCallbackMessageApiRest.getError());
        } catch (Exception e) {
            LogUtils.error("保存饿了么回调信息失败", ELEME_CONSUMER_THREAD_SIMPLE_NAME, "saveElemeCallbackMessage", e);
        }
    }
}
