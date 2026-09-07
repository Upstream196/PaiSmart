package com.yizhaoqi.smartpai.benchmark;

import org.knowm.xchart.BitmapEncoder;
import org.knowm.xchart.CategoryChart;
import org.knowm.xchart.CategoryChartBuilder;
import org.knowm.xchart.XYChart;
import org.knowm.xchart.XYChartBuilder;
import org.knowm.xchart.style.Styler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 图表生成器 —— 使用 XChart 生成 PNG 格式的对比图表。
 *
 * 生成 3 张图：
 * 1. Recall@K 对比柱状图
 * 2. Precision@K 对比柱状图
 * 3. 查询延迟分布折线图
 */
public class ChartGenerator {

    private static final String CHART_DIR = "target/benchmark-reports/charts";

    public static String generateAll(MetricsCollector.Summary pureSummary,
                                      MetricsCollector.Summary hybridSummary,
                                      List<MetricsCollector.QueryMetrics> pureRecords,
                                      List<MetricsCollector.QueryMetrics> hybridRecords,
                                      String pureName, String hybridName) throws IOException {
        Files.createDirectories(Paths.get(CHART_DIR));
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));

        StringBuilder md = new StringBuilder();

        Path recallChart = drawRecallComparison(pureSummary, hybridSummary, pureName, hybridName, timestamp);
        md.append("![Recall@K 对比](").append(recallChart.toAbsolutePath()).append(")\n\n");

        Path precisionChart = drawPrecisionComparison(pureSummary, hybridSummary, pureName, hybridName, timestamp);
        md.append("![Precision@K 对比](").append(precisionChart.toAbsolutePath()).append(")\n\n");

        Path latencyChart = drawLatencyDistribution(pureRecords, hybridRecords, pureName, hybridName, timestamp);
        md.append("![查询延迟分布](").append(latencyChart.toAbsolutePath()).append(")\n\n");

        return md.toString();
    }

    private static Path drawRecallComparison(MetricsCollector.Summary pure,
                                              MetricsCollector.Summary hybrid,
                                              String pureName, String hybridName,
                                              String timestamp) throws IOException {
        CategoryChart chart = new CategoryChartBuilder()
                .width(600).height(400)
                .title("Recall@K Comparison")
                .xAxisTitle("Metric")
                .yAxisTitle("Recall (%)")
                .build();

        chart.getStyler().setLegendPosition(Styler.LegendPosition.InsideNW);

        List<String> xLabels = List.of("Recall@5", "Recall@10");
        List<Double> pureVals = List.of(pure.recallAt5 * 100, pure.recallAt10 * 100);
        List<Double> hybridVals = List.of(hybrid.recallAt5 * 100, hybrid.recallAt10 * 100);

        chart.addSeries(pureName, xLabels, pureVals);
        chart.addSeries(hybridName, xLabels, hybridVals);

        Path file = Paths.get(CHART_DIR, "recall_comparison_" + timestamp + ".png");
        BitmapEncoder.saveBitmap(chart, file.toAbsolutePath().toString(), BitmapEncoder.BitmapFormat.PNG);
        return file;
    }

    private static Path drawPrecisionComparison(MetricsCollector.Summary pure,
                                                 MetricsCollector.Summary hybrid,
                                                 String pureName, String hybridName,
                                                 String timestamp) throws IOException {
        CategoryChart chart = new CategoryChartBuilder()
                .width(600).height(400)
                .title("Precision@K Comparison")
                .xAxisTitle("Metric")
                .yAxisTitle("Precision (%)")
                .build();

        chart.getStyler().setLegendPosition(Styler.LegendPosition.InsideNW);

        List<String> xLabels = List.of("Precision@5", "Precision@10");
        List<Double> pureVals = List.of(pure.precisionAt5 * 100, pure.precisionAt10 * 100);
        List<Double> hybridVals = List.of(hybrid.precisionAt5 * 100, hybrid.precisionAt10 * 100);

        chart.addSeries(pureName, xLabels, pureVals);
        chart.addSeries(hybridName, xLabels, hybridVals);

        Path file = Paths.get(CHART_DIR, "precision_comparison_" + timestamp + ".png");
        BitmapEncoder.saveBitmap(chart, file.toAbsolutePath().toString(), BitmapEncoder.BitmapFormat.PNG);
        return file;
    }

    private static Path drawLatencyDistribution(List<MetricsCollector.QueryMetrics> pureRecords,
                                                  List<MetricsCollector.QueryMetrics> hybridRecords,
                                                  String pureName, String hybridName,
                                                  String timestamp) throws IOException {
        XYChart chart = new XYChartBuilder()
                .width(800).height(400)
                .title("Query Latency Distribution")
                .xAxisTitle("Query #")
                .yAxisTitle("Latency (ms)")
                .build();

        chart.getStyler().setLegendPosition(Styler.LegendPosition.InsideNW);

        List<Integer> xPure = new ArrayList<>();
        List<Double> yPure = new ArrayList<>();
        for (int i = 0; i < pureRecords.size(); i++) {
            xPure.add(i);
            yPure.add((double) pureRecords.get(i).latencyMs);
        }

        List<Integer> xHybrid = new ArrayList<>();
        List<Double> yHybrid = new ArrayList<>();
        for (int i = 0; i < hybridRecords.size(); i++) {
            xHybrid.add(i);
            yHybrid.add((double) hybridRecords.get(i).latencyMs);
        }

        chart.addSeries(pureName, xPure, yPure);
        chart.addSeries(hybridName, xHybrid, yHybrid);

        Path file = Paths.get(CHART_DIR, "latency_distribution_" + timestamp + ".png");
        BitmapEncoder.saveBitmap(chart, file.toAbsolutePath().toString(), BitmapEncoder.BitmapFormat.PNG);
        return file;
    }
}
