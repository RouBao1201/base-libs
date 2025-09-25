package com.roubao.common.poi.utils;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.poi.excel.ExcelWriter;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.VerticalAlignment;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * @author SongYanBin
 * @since 2025/9/20
 **/
@Slf4j
public class ExcelUtil {

    public static <T> void exportXlsx(String[] headers,
                                      List<T> exportDataList,
                                      List<Function<T, Object>> getters,
                                      HttpServletResponse response) {
        ExcelWriter writer = null;
        ServletOutputStream outputStream = null;
        try {
            writer = cn.hutool.poi.excel.ExcelUtil.getWriter(true);
            writer.getStyleSet()
                    .setBorder(BorderStyle.NONE, IndexedColors.WHITE)
                    .setAlign(HorizontalAlignment.CENTER, VerticalAlignment.BOTTOM);
            writer.writeRow(List.of(headers));
            if (CollUtil.isNotEmpty(exportDataList)) {
                for (T it : exportDataList) {
                    writer.writeRow(() -> {
                        ArrayList<Object> rowDataList = new ArrayList<>(headers.length);
                        // 应用 Getter 方法
                        for (Function<T, Object> getter : getters) {
                            Object value = getter.apply(it);
                            rowDataList.add(value);
                        }
                        return rowDataList.iterator();
                    });
                }
            }
            response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            response.addHeader("Content-Disposition", "attachment; filename=export.xlsx");
            outputStream = response.getOutputStream();
            writer.flush(outputStream, true);
            outputStream.close();
            writer.close();
        } catch (Exception ex) {
            log.error("Excel export exception: {}", ex.getMessage(), ex);
            throw new RuntimeException(ex);
        } finally {
            if (outputStream != null) {
                try {
                    outputStream.close();
                } catch (Exception e) {
                    log.error("Exception to close output stream: {}", e.getMessage(), e);
                }
            }
            if (writer != null) {
                try {
                    writer.close();
                } catch (Exception e) {
                    log.error("Exception to close ExcelWriter: {}", e.getMessage(), e);
                }
            }
        }
    }


    @FunctionalInterface
    public interface RowDataConsumer<T> {
        void accept(List<Object> rowDataList, T t);
    }
}
