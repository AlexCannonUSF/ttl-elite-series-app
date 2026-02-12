package com.ttl.tabletennis.Utility;

import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartPanel;
import org.jfree.chart.JFreeChart;
import org.jfree.data.category.DefaultCategoryDataset;

import javax.swing.*;
import java.awt.*;

public class ChartUtils {

    /**
     * Creates a simple bar chart comparing player1 vs. player2’s wins in H2H.
     * You could expand to show total matches, last 5 matches, etc.
     */
    public static JPanel createHeadToHeadBarChart(String player1Name,
                                                  double player1WinPct,
                                                  String player2Name,
                                                  double player2WinPct) {
        DefaultCategoryDataset dataset = new DefaultCategoryDataset();

        // Convert decimal (0.33) to percentage (33)
        double p1Val = player1WinPct * 100;
        double p2Val = player2WinPct * 100;

        dataset.addValue(p1Val, player1Name, "Win %");
        dataset.addValue(p2Val, player2Name, "Win %");

        JFreeChart chart = ChartFactory.createBarChart(
                "Head-to-Head: " + player1Name + " vs. " + player2Name,
                "Players",
                "Win Percentage",
                dataset
        );

        // Optionally tweak chart appearance
        chart.setBackgroundPaint(Color.white);

        // Wrap it in a ChartPanel for Swing
        ChartPanel chartPanel = new ChartPanel(chart);
        chartPanel.setPreferredSize(new Dimension(400, 300));

        // Return a JPanel to embed in your dialogs
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(chartPanel, BorderLayout.CENTER);
        return panel;
    }
}
