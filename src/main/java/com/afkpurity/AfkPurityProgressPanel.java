package com.afkpurity;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import net.runelite.client.ui.PluginPanel;

/** Private sidebar only; never rendered on the game canvas. */
final class AfkPurityProgressPanel extends PluginPanel
{
    private final AfkPurityProgress progress;
    private final JTextArea report = new JTextArea();
    private final HistoryChart chart;

    AfkPurityProgressPanel(AfkPurityProgress progress, Runnable beginEpisode)
    {
        super(false);
        this.progress = progress;
        setLayout(new BorderLayout(0, 8));
        setBorder(BorderFactory.createEmptyBorder(10, 8, 10, 8));

        JPanel top = new JPanel(new BorderLayout(0, 6));
        JLabel heading = new JLabel("AFK PURITY · PROGRESS");
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 13f));
        top.add(heading, BorderLayout.NORTH);

        JPanel buttons = new JPanel(new java.awt.GridLayout(1, 2, 5, 0));
        JButton start = new JButton("New Episode");
        start.setToolTipText("Save a new baseline; lifetime counters are not reset");
        start.addActionListener(e -> {
            int result = JOptionPane.showConfirmDialog(this,
                    "Start a new episode? This replaces the previous episode baseline, but never resets lifetime totals.",
                    "New Episode", JOptionPane.YES_NO_OPTION);
            if (result == JOptionPane.YES_OPTION) beginEpisode.run();
        });
        JButton copy = new JButton("Copy Report");
        copy.addActionListener(e -> Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(report.getText()), null));
        buttons.add(start);
        buttons.add(copy);
        top.add(buttons, BorderLayout.SOUTH);
        add(top, BorderLayout.NORTH);

        JPanel middle = new JPanel(new BorderLayout(0, 8));
        chart = new HistoryChart(progress);
        chart.setPreferredSize(new Dimension(220, 150));
        middle.add(chart, BorderLayout.NORTH);
        report.setEditable(false);
        report.setLineWrap(true);
        report.setWrapStyleWord(true);
        report.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        report.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        middle.add(new JScrollPane(report), BorderLayout.CENTER);
        add(middle, BorderLayout.CENTER);
        refresh();
    }

    void refresh()
    {
        if (!SwingUtilities.isEventDispatchThread())
        {
            SwingUtilities.invokeLater(this::refresh);
            return;
        }
        report.setText(progress.report());
        report.setCaretPosition(0);
        chart.repaint();
    }

    private static final class HistoryChart extends JPanel
    {
        private final AfkPurityProgress progress;
        HistoryChart(AfkPurityProgress progress) { this.progress = progress; }
        @Override protected void paintComponent(Graphics graphics)
        {
            super.paintComponent(graphics);
            Graphics2D g = (Graphics2D) graphics.create();
            try
            {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int w = getWidth(), h = getHeight();
                g.setColor(new Color(42, 43, 46));
                g.fillRoundRect(0, 0, w, h, 8, 8);
                g.setColor(Color.WHITE);
                g.drawString("AFK Purity · Daily History", 10, 18);
                List<AfkPurityProgress.DayPoint> points = progress.getHistory();
                if (points.size() < 2)
                {
                    g.setColor(Color.LIGHT_GRAY);
                    g.drawString("History appears after two days", 10, h / 2);
                    return;
                }
                int x0 = 12, x1 = w - 12, y0 = 30, y1 = h - 22;
                g.setColor(new Color(90, 92, 96));
                g.drawLine(x0, y1, x1, y1);
                double min = 100.0, max = 0.0;
                for (AfkPurityProgress.DayPoint p : points)
                {
                    min = Math.min(min, p.purity);
                    max = Math.max(max, p.purity);
                }
                min = Math.max(0, Math.floor(min - 1));
                max = Math.min(100, Math.ceil(max + 1));
                if (max <= min) max = min + 1;
                g.setColor(new Color(104, 212, 131));
                g.setStroke(new BasicStroke(2f));
                for (int i = 1; i < points.size(); i++)
                {
                    int xa = x0 + (i - 1) * (x1 - x0) / (points.size() - 1);
                    int xb = x0 + i * (x1 - x0) / (points.size() - 1);
                    int ya = y1 - (int) ((points.get(i - 1).purity - min) * (y1 - y0) / (max - min));
                    int yb = y1 - (int) ((points.get(i).purity - min) * (y1 - y0) / (max - min));
                    g.drawLine(xa, ya, xb, yb);
                }
                g.setColor(Color.LIGHT_GRAY);
                g.drawString(String.format(java.util.Locale.ROOT, "%.1f%%–%.1f%%", min, max), 10, h - 5);
            }
            finally { g.dispose(); }
        }
    }
}
