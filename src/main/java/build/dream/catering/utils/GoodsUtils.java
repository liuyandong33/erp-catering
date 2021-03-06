package build.dream.catering.utils;

import build.dream.catering.mappers.GoodsMapper;
import build.dream.common.utils.ApplicationHandler;
import build.dream.common.utils.ValidateUtils;

import java.math.BigDecimal;
import java.math.BigInteger;

public class GoodsUtils {
    private static GoodsMapper goodsMapper;

    private static GoodsMapper obtainGoodsMapper() {
        if (goodsMapper == null) {
            goodsMapper = ApplicationHandler.getBean(GoodsMapper.class);
        }
        return goodsMapper;
    }

    public static BigDecimal deductingGoodsStock(BigInteger goodsId, BigInteger goodsSpecificationId, BigDecimal quantity) {
        BigDecimal stock = obtainGoodsMapper().deductingGoodsStock(goodsId, goodsSpecificationId, quantity);
        ValidateUtils.isTrue(stock.compareTo(BigDecimal.ZERO) >= 0, "库存不足！");
        return stock;
    }

    public static BigDecimal addGoodsStock(BigInteger goodsId, BigInteger goodsSpecificationId, BigDecimal quantity) {
        BigDecimal stock = obtainGoodsMapper().addGoodsStock(goodsId, goodsSpecificationId, quantity);
        return stock;
    }
}
