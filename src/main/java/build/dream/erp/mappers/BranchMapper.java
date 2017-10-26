package build.dream.erp.mappers;

import build.dream.common.erp.domains.Branch;
import build.dream.common.utils.SearchModel;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;

@Mapper
public interface BranchMapper {
    long insert(Branch branch);
    long insertAll(List<Branch> branches);
    long update(Branch branch);
    long insertMergeUserBranch(@Param("userId") BigInteger userId, @Param("tenantId") BigInteger tenantId, @Param("branchId") BigInteger branchId);
    List<Branch> findAll(SearchModel searchModel);
    Branch find(SearchModel searchModel);
    Branch findByUserIdAndTenantId(@Param("tenantId") BigInteger tenantId, @Param("userId") BigInteger userId);
    List<BigInteger> findAllUserIds(SearchModel searchModel);
    long countUsers(SearchModel searchModel);
    long countBranches(SearchModel searchModel);
    List<Map<String, Object>> findAllBranchInfos();
    Branch findBranchInfo(@Param("tenantId") BigInteger tenantId, @Param("userId") BigInteger userId);
}
