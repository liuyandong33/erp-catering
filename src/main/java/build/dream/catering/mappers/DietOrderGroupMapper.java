package build.dream.catering.mappers;

import build.dream.common.erp.catering.domains.DietOrderGroup;
import build.dream.common.utils.SearchModel;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface DietOrderGroupMapper {
    long insert(DietOrderGroup dietOrderGroup);
    DietOrderGroup find(SearchModel searchModel);
    List<DietOrderGroup> findAll(SearchModel searchModel);
}