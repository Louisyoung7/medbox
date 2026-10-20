  package com.medbox.server.controller;

  import com.medbox.server.dto.R;
  import com.medbox.server.dto.medicine.StockInRequest;
  import com.medbox.server.dto.medicine.StockItemResponse;
  import com.medbox.server.dto.medicine.StockSaveRequest;
  import com.medbox.server.service.MedicineStockService;
  import jakarta.validation.Valid;
  import java.util.List;
  import org.springframework.web.bind.annotation.DeleteMapping;
  import org.springframework.web.bind.annotation.GetMapping;
  import org.springframework.web.bind.annotation.PathVariable;
  import org.springframework.web.bind.annotation.PostMapping;
  import org.springframework.web.bind.annotation.PutMapping;
  import org.springframework.web.bind.annotation.RequestBody;
  import org.springframework.web.bind.annotation.RequestMapping;
  import org.springframework.web.bind.annotation.RestController;

  /**
   * 药箱库存接口（见文档 02 第 5 章 / 06 的 2.5）。
   *
   * <p>库存按「哪个药箱里的哪种药」管理，读写权限都以 {@code deviceId}
  在
   * {@link MedicineStockService} 里用 {@code requireDevice(...)} 判定。
   */

  @RestController
  @RequestMapping("/api/v1/devices/{deviceId}/stocks")
  public class MedicineStockController{
    private final MedicineStockService medicineStockService;
    public MedicineStockController(MedicineStockService medicineStockService){
        this.medicineStockService=medicineStockService;
    }
    /** 该药箱的库存清单（带药名）。 */
    @GetMapping
    public R<List<StockItemResponse>>list(@PathVariable("deviceId")String deviceId){
        return R.ok(medicineStockService.list(deviceId));
    }

/** 录入 / 更新某药品库存，数量整体覆盖。 */
    @PutMapping("/{medicineId}")
    public R<StockItemResponse>save(
        @PathVariable("deviceId")String deviceId,
        @PathVariable("medicineId")String medicineId,
        @Valid @RequestBody StockSaveRequest request
    ){
        return R.ok(medicineStockService.save(deviceId,medicineId,request));
    }
    
    /** 补药入库，数量增量累加。 */
    @PostMapping("/{medicineId}/in")
    public R<StockItemResponse>in(
        @PathVariable("deviceId")String deviceId,
        @PathVariable("medicineId")String medicineId,
        @Valid @RequestBody StockInRequest request
    ){
        return R.ok(medicineStockService.in(deviceId,medicineId,request));
    }
    /** 移除该药品库存。 */
    @DeleteMapping("/{medicineId}")
    public R<Void>delete(
        @PathVariable("deviceId")String deviceId,
        @PathVariable("medicineId")String medicineId
    ){
        medicineStockService.delete(deviceId,medicineId);
        return R.ok();
    }

  }