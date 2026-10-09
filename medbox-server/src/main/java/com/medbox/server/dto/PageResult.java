package com.medbox.server.dto;

import java.util.Collections;
import java.util.List;

/**
 * 分页包装：所有列表接口统一返回 {@code R<PageResult<T>>}（见文档 02 的 1「分页」）。
 *
 * <pre>{@code
 * {
 *   "code": 0,
 *   "data": { "page": 1, "size": 20, "total": 137, "list": [ ... ] }
 * }
 * }</pre>
 */
public final class PageResult<T> {

    /** 当前页码，从 1 起。 */
    private final int page;

    /** 每页条数。 */
    private final int size;

    /** 总条数。 */
    private final long total;

    /** 当前页数据。 */
    private final List<T> list;

    private PageResult(int page, int size, long total, List<T> list) {
        this.page = page;
        this.size = size;
        this.total = total;
        this.list = list;
    }

    public static <T> PageResult<T> of(List<T> list, long total, int page, int size) {
        return new PageResult<>(page, size, total, list == null ? Collections.emptyList() : list);
    }

    public static <T> PageResult<T> empty(int page, int size) {
        return new PageResult<>(page, size, 0, Collections.emptyList());
    }

    public int getPage() {
        return page;
    }

    public int getSize() {
        return size;
    }

    public long getTotal() {
        return total;
    }

    public List<T> getList() {
        return list;
    }
}
