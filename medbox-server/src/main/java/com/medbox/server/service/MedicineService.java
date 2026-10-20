  package com.medbox.server.service;

  import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
  import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
  import com.medbox.server.common.exception.BizAssert;
  import com.medbox.server.common.security.Permission;
  import com.medbox.server.domain.Medicine;
  import com.medbox.server.domain.MedicineStock;
  import com.medbox.server.dto.PageResult;
  import com.medbox.server.dto.medicine.MedicineResponse;
  import com.medbox.server.dto.medicine.MedicineSaveRequest;
  import com.medbox.server.dto.medicine.MedicineSummaryResponse;
  import com.medbox.server.mapper.MedicineMapper;
  import com.medbox.server.mapper.MedicineStockMapper;
  import java.time.Clock;
  import java.time.OffsetDateTime;
  import java.util.List;
  import java.util.Set;
  import org.springframework.beans.factory.annotation.Autowired;
  import org.springframework.stereotype.Service;
  import org.springframework.transaction.annotation.Transactional;

  /**
   * 药品档案 CRUD（见文档 02 第 5 章、06 的 2.4）。
   * 可见范围 = 公共药品（ownerElderId 为空）+ 我监护老人的私有药品；
   * 写权限 = 公共药品要 FAMILY 监护人，私有药品要该老人的 FAMILY
  监护人。
   */

@Service
public class MedicineService {
    private static final int DEFAULT_PAGE_SIZE=20;
    private static final int MAX_PAGE_SIZE=100;

    private final MedicineMapper medicineMapper;
    private final MedicineStockMapper medicineStockMapper;
    private final AccessService accessService;
    private final Clock clock;

    @Autowired
    public MedicineService(MedicineMapper medicineMapper, MedicineStockMapper medicineStockMapper, AccessService accessService) {
        this(medicineMapper,medicineStockMapper,accessService,Clock.systemUTC());
    }

    public MedicineService(MedicineMapper medicineMapper, MedicineStockMapper medicineStockMapper, AccessService accessService, Clock clock) {
        this.medicineMapper = medicineMapper;
        this.medicineStockMapper = medicineStockMapper;
        this.accessService = accessService;
        this.clock = clock;
    }

    /** POST /medicines：medicineId 为空 = 新建，带了 = 更新。 */
    @Transactional
    public MedicineResponse save(MedicineSaveRequest request){
        boolean isNew =request.medicineId()==null||request.medicineId().isBlank();
        return isNew?create(request):update(request);
    }

     /** GET /medicines：分页 + 按名称搜索，可见 = 公共 +我监护老人的私有。 */
     public PageResult<MedicineSummaryResponse> list(String keyword,Integer page ,Integer size){
        int safePage=page==null?1:page;
        int safeSize=size==null?DEFAULT_PAGE_SIZE:size;

        BizAssert.badRequestIf(safePage<1,"page 需从 1 开始");
        BizAssert.badRequestIf(safeSize<1,"size 需大于 0");

        safeSize=Math.min(safeSize,MAX_PAGE_SIZE);

        Set<String> elderIds=accessService.visibleElderIds();
        LambdaQueryWrapper<Medicine> query=new LambdaQueryWrapper<Medicine>().and(w->{
            w.isNull(Medicine::getOwnerElderId);
            if(!elderIds.isEmpty()){
                w.or().in(Medicine::getOwnerElderId,elderIds);
            }
        }).orderByAsc(Medicine::getMedicineId);
        if(keyword!=null&&!keyword.isBlank()){
            query.like(Medicine::getName,keyword.trim());
        }
        Page<Medicine> result=medicineMapper.selectPage(new Page<>((long)safePage,safeSize),query);
        List<MedicineSummaryResponse> list=result.getRecords().stream().map(MedicineSummaryResponse::from).toList();
        return PageResult.of(list,result.getTotal(),safePage,safeSize);
     }

     /** GET /medicines/{medicineId}：详情。 */
     public MedicineResponse detail(String medicineId){
        return MedicineResponse.from(requireForRead(medicineId));
     }

    /** DELETE /medicines/{medicineId}：有库存引用 → 40901。 */
    @Transactional
    public void delete(String medicineId){
        Medicine medicine=requireForWrite(medicineId);
        Long stockCount=medicineStockMapper.selectCount(new LambdaQueryWrapper<MedicineStock>().eq(MedicineStock::getMedicineId,medicineId));
        BizAssert.conflictIf(stockCount!=null&&stockCount>0,"该药品仍被库存引用，无法删除");
        medicineMapper.deleteById(medicine.getId());
    }

    private MedicineResponse create(MedicineSaveRequest request){

        String owner=normalizeOwner(request.ownerElderId());
        requireWriteByOwner(owner);

        Medicine medicine=new Medicine();
        medicine.setMedicineId(medicineMapper.nextMedicineId());
        medicine.setOwnerElderId(owner);
        medicine.setName(request.name());
        medicine.setSpec(request.spec());
        medicine.setDosePerTime(request.dosePerTime());
        medicine.setUnit(request.unit());
        medicine.setContraindication(request.contraindication());
        medicine.setStorageCond(request.storageCond());
        medicine.setCreatedAt(OffsetDateTime.now(clock));
        medicineMapper.insert(medicine);
        return MedicineResponse.from(medicine);
    }

    private MedicineResponse update(MedicineSaveRequest request) {
        Medicine medicine = requireForWrite(request.medicineId());
        // ownerElderId 归属不可变：忽略请求里带的 ownerElderId
        medicine.setName(request.name());
        medicine.setSpec(request.spec());
        medicine.setDosePerTime(request.dosePerTime());
        medicine.setUnit(request.unit());
        medicine.setContraindication(request.contraindication());
        medicine.setStorageCond(request.storageCond());
        medicineMapper.updateById(medicine);
        return MedicineResponse.from(medicine);
      }
    /** 读：不存在或不可见一律 40301（防枚举），私有药再走
  READ_MEDICINE。 */
      private Medicine requireForRead(String medicineId) {
        Medicine medicine = find(medicineId);
        if (medicine.getOwnerElderId() != null) {
            accessService.require(medicine.getOwnerElderId(),Permission.READ_MEDICINE);
        }
        return medicine;
    }
    /** 写：不存在一律 40301，再按公共/私有判写权限。 */
      private Medicine requireForWrite(String medicineId) {
        Medicine medicine = find(medicineId);
        requireWriteByOwner(medicine.getOwnerElderId());
        return medicine;
    }

    private Medicine find(String medicineId){
        Medicine medicine=medicineMapper.selectOne(new LambdaQueryWrapper<Medicine>().eq(Medicine::getMedicineId,medicineId));
        BizAssert.forbiddenIf(medicine==null,"无权限访问该资源");
        return medicine;
    }

    /** 公共（owner 为空）→ requireFamilyGuardian；私有 → 该老人WRITE_MEDICINE。 */
      private void requireWriteByOwner(String ownerElderId) {
        if (ownerElderId == null) {
            accessService.requireFamilyGuardian();
        } else {
            accessService.require(ownerElderId,Permission.WRITE_MEDICINE);
        }
    }


    private String normalizeOwner(String ownerElderId){
        if(ownerElderId==null||ownerElderId.isBlank()){
            return null;
        }
        return ownerElderId.trim();
    }
}


