/*
 * Copyright 2021 Ben Manes. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.benmanes.caffeine.cache.simulator.report.csv;

import static java.awt.Font.BOLD;
import static java.awt.Font.PLAIN;
import static java.util.Objects.requireNonNull;
import static org.jfree.chart.plot.DefaultDrawingSupplier.DEFAULT_FILL_PAINT_SEQUENCE;
import static org.jfree.chart.plot.DefaultDrawingSupplier.DEFAULT_OUTLINE_PAINT_SEQUENCE;
import static org.jfree.chart.plot.DefaultDrawingSupplier.DEFAULT_OUTLINE_STROKE_SEQUENCE;
import static org.jfree.chart.plot.DefaultDrawingSupplier.DEFAULT_SHAPE_SEQUENCE;
import static org.jfree.chart.plot.DefaultDrawingSupplier.DEFAULT_STROKE_SEQUENCE;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.io.IOException;
import java.io.Serializable;
import java.io.UncheckedIOException;
import java.nio.file.Path;

import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartUtils;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.StandardChartTheme;
import org.jfree.chart.axis.Axis;
import org.jfree.chart.plot.CategoryPlot;
import org.jfree.chart.plot.DefaultDrawingSupplier;
import org.jfree.chart.ui.RectangleInsets;
import org.jfree.data.Range;
import org.jfree.data.category.CategoryDataset;
import org.jfree.data.category.DefaultCategoryDataset;

import com.google.common.base.Strings;
import com.google.common.collect.Iterables;
import com.google.common.primitives.Doubles;
import com.google.errorprone.annotations.Var;

import de.siegmar.fastcsv.reader.CsvReader;

/**
 * A utility that generates a line chart from the csv format produced by {@link CombinedCsvReport}.
 * <p>
 * Based on <a href="https://github.com/eobermuhlner/csv2chart">csv2chart</a>.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public record PlotCsv(Path inputFile, Path outputFile, String metric,
    String title, ChartStyle style) implements Runnable {

  public PlotCsv {
    requireNonNull(outputFile);
    requireNonNull(inputFile);
    requireNonNull(metric);
    requireNonNull(title);
    requireNonNull(style);
  }

  @Override
  public void run() {
    var chart = ChartFactory.createLineChart(title, "Maximum Size", metric, data());
    chart.setTextAntiAlias(true);
    chart.setAntiAlias(true);

    applyTheme(chart);
    configurePlot(chart);

    try {
      ChartUtils.saveChartAsPNG(outputFile.toFile(), chart, 1280, 720);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private CategoryDataset data() {
    try (var reader = CsvReader.builder().ofNamedCsvRecord(inputFile)) {
      var dataset = new DefaultCategoryDataset();
      for (var record : reader) {
        for (var column : Iterables.skip(record.getHeader(), 1)) {
          var value = record.findField(column).map(Doubles::tryParse).orElse(null);
          dataset.addValue(value, record.getField(0), column);
        }
      }
      return dataset;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private void configurePlot(JFreeChart chart) {
    var plot = (CategoryPlot) chart.getPlot();
    configureAxis(plot.getDomainAxis());
    configureAxis(plot.getRangeAxis());
    configureGrid(plot);

    plot.getRangeAxis().setAutoRange(false);
    plot.getRangeAxis().setRange(calculateRange(plot));

    for (int i = 0; i < plot.getCategories().size(); i++) {
      plot.getRenderer().setSeriesStroke(i, new BasicStroke(3.0f));
    }
  }

  private static Range calculateRange(CategoryPlot plot) {
    @Var double upperBound = 0;
    @Var double lowerBound = 100;
    for (int series = 0; series < plot.getDataset().getRowCount(); series++) {
      for (int item = 0; item < plot.getDataset().getColumnCount(); item++) {
        var value = plot.getDataset().getValue(series, item);
        if (value != null) {
          lowerBound = Math.min(lowerBound, value.doubleValue());
          upperBound = Math.max(upperBound, value.doubleValue());
        }
      }
    }
    double margin = 0.1 * (upperBound - lowerBound);
    return new Range(Math.max(0, lowerBound - margin), Math.min(100, upperBound + margin));
  }

  private void applyTheme(JFreeChart chart) {
    var theme = (StandardChartTheme) StandardChartTheme.createJFreeTheme();
    theme.setDrawingSupplier(new DefaultDrawingSupplier(getWheelColors(),
        DEFAULT_FILL_PAINT_SEQUENCE, DEFAULT_OUTLINE_PAINT_SEQUENCE, DEFAULT_STROKE_SEQUENCE,
        DEFAULT_OUTLINE_STROKE_SEQUENCE, DEFAULT_SHAPE_SEQUENCE));

    theme.setPlotBackgroundPaint(style.background());
    theme.setChartBackgroundPaint(style.background());
    theme.setLegendBackgroundPaint(style.background());

    theme.setTitlePaint(style.label());
    theme.setSubtitlePaint(style.axisLabel());
    theme.setLegendItemPaint(style.legend());
    theme.setItemLabelPaint(style.label());
    theme.setAxisLabelPaint(style.axisLabel());
    theme.setTickLabelPaint(style.axisLabel());
    theme.setRangeGridlinePaint(style.gridLine());
    theme.setDomainGridlinePaint(style.gridLine());
    theme.setGridBandPaint(style.gridBand());

    theme.setExtraLargeFont(style.extraLargeFont());
    theme.setRegularFont(style.regularFont());
    theme.setLargeFont(style.largeFont());

    if (Strings.isNullOrEmpty(title)) {
      theme.setAxisOffset(style.axisOffset());
    }

    theme.apply(chart);
  }

  private void configureGrid(CategoryPlot plot) {
    plot.setDomainGridlineStroke(new BasicStroke());
    plot.setDomainGridlinePaint(style.gridLine());
    plot.setDomainGridlinesVisible(true);

    plot.setRangeGridlineStroke(new BasicStroke());
    plot.setRangeGridlinePaint(style.gridLine());
    plot.setRangeGridlinesVisible(true);

    plot.setOutlineVisible(false);
  }

  private void configureAxis(Axis axis) {
    axis.setAxisLineVisible(true);
    axis.setTickMarksVisible(true);
    axis.setAxisLinePaint(style.axisLine());
    axis.setTickMarkPaint(style.axisLine());
    axis.setTickLabelPaint(style.axisLine());
  }

  private Color[] getWheelColors() {
    @Var int wheelStep = 3;
    @Var int paintIndex = 0;
    var colors = new Color[360];
    var wheelPaints = new boolean[colors.length];
    while (paintIndex < colors.length) {
      int step = (colors.length / wheelStep);
      for (int angle = 0; angle < colors.length; angle += step) {
        if (!wheelPaints[angle]) {
          wheelPaints[angle] = true;
          float hue = ((float) angle) / colors.length;
          colors[paintIndex++] = getLineColor(hue);
        }
      }
      wheelStep += wheelStep;
    }
    return colors;
  }

  private Color getLineColor(float hue) {
    int rgb = 0xffffff & Color.HSBtoRGB(hue, style.saturation(), style.brightness());
    int a = ((int) (style.alpha() * 255.0 + 0.5)) << 24;
    return new Color(rgb | a, /* hasalpha= */  true);
  }

  public record ChartStyle(RectangleInsets axisOffset, Font extraLargeFont, Font regularFont,
      Font largeFont, Color background, Color axisLabel, Color axisLine, Color subtitle,
      float brightness, float saturation, Color legend, Color label, Color title, Color gridLine,
      Color gridBand, float alpha) implements Serializable {
    public ChartStyle {
      requireNonNull(extraLargeFont);
      requireNonNull(regularFont);
      requireNonNull(axisOffset);
      requireNonNull(background);
      requireNonNull(largeFont);
      requireNonNull(axisLabel);
      requireNonNull(axisLine);
      requireNonNull(gridLine);
      requireNonNull(gridBand);
      requireNonNull(subtitle);
      requireNonNull(legend);
      requireNonNull(label);
      requireNonNull(title);
    }
    public static ChartStyle forColors(Color background, Color content, Color grid) {
      return new ChartStyle(
          /* axisOffset= */ new RectangleInsets(20, 20, 20, 20),
          /* extraLargeFont= */ new Font("Helvetica", BOLD, 18),
          /* regularFont= */ new Font("Helvetica", PLAIN, 12),
          /* largeFont= */ new Font("Helvetica", BOLD, 14),
          /* background= */ background,
          /* axisLabel= */ content,
          /* axisLine= */ content,
          /* subtitle= */ content,
          /* brightness= */ 0.6f,
          /* saturation= */ 0.7f,
          /* legend= */ content,
          /* label= */ content,
          /* title= */ content,
          /* gridLine= */ grid,
          /* gridBand= */ grid,
          /* alpha= */ 0.8f);
    }
    public static ChartStyle light() {
      var grid = new Color(0xeee8d5);
      var content = new Color(0x585858);
      var background = new Color(0xfdf6e3);
      return ChartStyle.forColors(background, content, grid);
    }
    public static ChartStyle dark() {
      var grid = new Color(0x073642);
      var content = new Color(0x93a1a1);
      var background = new Color(0x002b36);
      return ChartStyle.forColors(background, content, grid);
    }
  }
}
