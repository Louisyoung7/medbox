package com.medbox.server.service;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.medbox.server.common.exception.BizAssert;
import com.medbox.server.common.security.AccessIdentity;
import com.medbox.server.common.security.Permission;
import com.medbox.server.domain.Medicine;
import com.medbox.server.domain.MedicineStock;
import com.medbox.server.dto.medicine.StockInRequest;
import com.medbox.server.dto.medicine.StockItemResponse;
import com.medbox.server.dto.medicine.StockSaveRequest;
import com.medbox.server.mapper.MedicineMapper;
import com.medbox.server.mapper.MedicineStockMapper;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 药箱库存（见文档 02 第 5 章、06 的 2.5）：按「药箱 +
药品」一条记录，
 * 数量 / 生产日期 / 有效期都挂在这条记录上，不划分仓位 / 格子。
 */

@Service
public class MedicineStockService {
    private final MedicineStockMapper medicineStockMapper;
    private final MedicineMapper medicineMapper;
    private final AccessService accessService;
    private final Clock clock;

    @Autowired
    public MedicineStockService(MedicineStockMapper medicineStockMapper,MedicineMapper medicineMapper,AccessService accessService){
        this(medicineStockMapper,medicineMapper,accessService,Clock.systemUTC());
    }

    public MedicineStockService(MedicineStockMapper medicineStockMapper, MedicineMapper medicineMapper, AccessService accessService, Clock clock) {
        this.medicineStockMapper = medicineStockMapper;
        this.medicineMapper = medicineMapper;
        this.accessService = accessService;
        this.clock = clock;
    }

    /** GET /devices/{deviceId}/stocks：该药箱的库存清单。 */
    public List<StockItemResponse> list(String deviceId) {
       accessService.requireDevice(deviceId,Permission.READ_MEDICINE);
       List<MedicineStock> stocks=medicineStockMapper.selectList(new LambdaQueryWrapper<MedicineStock>().eq(MedicineStock::getDeviceId,deviceId).orderByAsc(MedicineStock::getMedicineId));
       if(stocks.isEmpty()){
           return List.of();
       }

       Map<String,String>names=medicineNames(stocks.stream().map(MedicineStock::getMedicineId).toList());
       return stocks.stream().map(s->StockItemResponse.from(s,names.get(s.getMedicineId()))).toList();
       }

    /** PUT/devices/{deviceId}/stocks/{medicineId}：录入/更新，数量整体覆盖。 */
    @Transactional
    public StockItemResponse save(String deviceId,String medicineId,StockSaveRequest request){
        AccessIdentity identity=accessService.requireDevice(deviceId,Permission.WRITE_MEDICINE);
        Medicine medicine=requireMedicine(medicineId);
        MedicineStock stock=findStock(deviceId,medicineId);
        if(stock==null){
            stock=newStock(identity.elderId(),deviceId,medicineId,request.quantity(),request.productionDate(),request.expiryDate());
            medicineStockMapper.insert(stock);
        }else{
            stock.setQuantity(request.quantity());
            if(request.productionDate()!=null){
                stock.setProductionDate(request.productionDate());
            }
            if(request.expiryDate()!=null){
                stock.setExpiryDate(request.expiryDate());
            }

            stock.setUpdatedAt(OffsetDateTime.now(clock));
            medicineStockMapper.updateById(stock);
        }
        return StockItemResponse.from(stock,medicine.getName());
    }
    /** POST/devices/{deviceId}/stocks/{medicineId}/in：补药入库，数量增量累加。*/
    @Transactional
    public StockItemResponse in(String deviceId,String medicineId,StockInRequest request){
        AccessIdentity identity=accessService.requireDevice(deviceId,Permission.WRITE_MEDICINE);
        Medicine medicine=requireMedicine(medicineId);
        MedicineStock stock=findStock(deviceId,medicineId);
        if(stock==null){
            stock=newStock(identity.elderId(),deviceId,medicineId,request.quantity(),request.productionDate(),request.expiryDate());
            medicineStockMapper.insert(stock);
        }else{
            int current =stock.getQuantity()==null?0:stock.getQuantity();
            stock.setQuantity(current+request.quantity());
            if(request.productionDate()!=null){
                stock.setProductionDate(request.productionDate());
            }
            if(request.expiryDate()!=null){
                stock.setExpiryDate(request.expiryDate());
            }
            stock.setUpdatedAt(OffsetDateTime.now(clock));
            medicineStockMapper.updateById(stock);
        }
        
        return StockItemResponse.from(stock,medicine.getName());
    }

    /** DELETE/devices/{deviceId}/stocks/{medicineId}：移除该药品库存。 */
    @Transactional
    public void delete(String deviceId,String medicineId){
        accessService.requireDevice(deviceId,Permission.WRITE_MEDICINE);
        MedicineStock stock=findStock(deviceId,medicineId);
        BizAssert.notFound(stock,"药品库存不存在");
        medicineStockMapper.deleteById(stock.getId());
    }

    private MedicineStock findStock(String deviceId,String medicineId){
        return medicineStockMapper.selectOne(new LambdaQueryWrapper<MedicineStock>().eq(MedicineStock::getDeviceId,deviceId).eq(MedicineStock::getMedicineId,medicineId));
}

    /** 库存引用的药品必须存在，否则 40401（逻辑外键）。 */
    private Medicine requireMedicine(String medicineId){
        Medicine medicine=medicineMapper.selectOne(new LambdaQueryWrapper<Medicine>().eq(Medicine::getMedicineId,medicineId));
        BizAssert.notFound(medicine,"药品不存在");
        return medicine;
    }

    private MedicineStock newStock(String elderId,String deviceId,String medicineId,int quantity,LocalDate productionDate,LocalDate expiryDate){
        MedicineStock stock=new MedicineStock();
        stock.setStockId(medicineStockMapper.nextStockId());
        stock.setDeviceId(deviceId);
        stock.setElderId(elderId);
        stock.setMedicineId(medicineId);
        stock.setQuantity(quantity);
        stock.setProductionDate(productionDate);
        stock.setExpiryDate(expiryDate);
        stock.setUpdatedAt(OffsetDateTime.now(clock));
        return stock;
    }

    /** 批量查药名，避免列表逐条查库的 N+1。 */
    private Map<String,String> medicineNames(List<String> medicineIds){
        if(medicineIds.isEmpty()){

            return Map.of();
        }
        return medicineMapper.selectList(new LambdaQueryWrapper<Medicine>().in(Medicine::getMedicineId,medicineIds)).stream().collect(Collectors.toMap(Medicine::getMedicineId,Medicine::getName));
    }

}
