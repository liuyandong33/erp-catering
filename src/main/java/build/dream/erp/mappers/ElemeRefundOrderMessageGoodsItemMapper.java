package build.dream.erp.mappers;

import build.dream.common.erp.catering.domains.ElemeRefundOrderMessageGoodsItem;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ElemeRefundOrderMessageGoodsItemMapper {
    long insert(ElemeRefundOrderMessageGoodsItem elemeRefundOrderMessageGoodsItem);
}
