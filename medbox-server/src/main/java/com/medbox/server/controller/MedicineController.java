  package com.medbox.server.controller;

  import com.medbox.server.dto.PageResult;
  import com.medbox.server.dto.R;
  import com.medbox.server.dto.medicine.MedicineResponse;
  import com.medbox.server.dto.medicine.MedicineSaveRequest;
  import com.medbox.server.dto.medicine.MedicineSummaryResponse;
  import com.medbox.server.service.MedicineService;
  import jakarta.validation.Valid;
  import org.springframework.web.bind.annotation.DeleteMapping;
  import org.springframework.web.bind.annotation.GetMapping;
  import org.springframework.web.bind.annotation.PathVariable;
  import org.springframework.web.bind.annotation.PostMapping;
  import org.springframework.web.bind.annotation.RequestBody;
  import org.springframework.web.bind.annotation.RequestMapping;
  import org.springframework.web.bind.annotation.RequestParam;
  import org.springframework.web.bind.annotation.RestController;

  /**
   * 药品档案接口（见文档 02 第 5 章）。
   *
   * <p>只做参数绑定与 {@code R<T>} 包装，一切可见范围 / 写权限判定都在
   * {@link MedicineService} 里；未登录由 {@code AuthInterceptor} 统一兜
  40101。
   */

@RestController
@RequestMapping("/api/v1/medicines")
public class MedicineController {
    private final MedicineService medicineService;
    public MedicineController(MedicineService medicineService){
        this.medicineService=medicineService;
    }
 /** 新建 / 更新药品：medicineId 留空 = 新建，带了 = 更新（无单独PUT）。 */
  @PostMapping
  public R<MedicineResponse>save(@Valid @RequestBody MedicineSaveRequest request){
    return R.ok(medicineService.save(request));
  }

/** 分页列表 + 按名称搜索，可见 = 公共药品 +我监护老人的私有药品。 */
@GetMapping
public R<PageResult<MedicineSummaryResponse>>list(
    @RequestParam(value="keyword",required=false)String keyword,
    @RequestParam(value="page",required=false)Integer page,
    @RequestParam(value="size",required=false)Integer size
){
    return R.ok(medicineService.list(keyword,page,size));
}

/** 药品详情（含禁忌 / 储存条件 / 创建时间）。 */
@GetMapping("/{medicineId}")
public R<MedicineResponse> detail(@PathVariable ("medicineId") String medicineId){
    return R.ok(medicineService.detail(medicineId));
}

/** 删除药品；仍被库存引用 → 40901。 */
@DeleteMapping("/{medicineId}")
public R<Void>delete (@PathVariable ("medicineId")String medicineId){
    medicineService.delete(medicineId);
    return R.ok();
}

}

