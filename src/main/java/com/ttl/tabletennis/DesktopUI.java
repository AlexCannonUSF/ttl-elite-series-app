package com.ttl.tabletennis;

import com.ttl.tabletennis.domain.Match;
import com.ttl.tabletennis.domain.ParlayMatch;
import com.ttl.tabletennis.domain.Player;
import com.ttl.tabletennis.repository.MatchRepository;
import com.ttl.tabletennis.repository.PlayerRepository;
import com.ttl.tabletennis.service.PlayerService;
import com.ttl.tabletennis.service.StatisticsService;
import com.ttl.tabletennis.scrape.TtSeriesScraper;
import com.ttl.tabletennis.util.ChartUtils;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

public class DesktopUI extends JFrame {
    private final PlayerService playerService;
    private final StatisticsService statisticsService;
    private final TtSeriesScraper scraper;
    private final MatchRepository matchRepository;
    private final PlayerRepository playerRepository;
    // List for editing odds
    private final List<ParlayMatch> parlayMatches = new ArrayList<>();
    // List for saved matchup analyses
    private final List<SavedMatchup> savedMatchups = new ArrayList<>();

    private JTextArea textArea = null;

    public DesktopUI(PlayerService playerService, StatisticsService statisticsService,
                     TtSeriesScraper scraper, MatchRepository matchRepository,
                     PlayerRepository playerRepository) {
        this.playerService = playerService;
        this.statisticsService = statisticsService;
        this.scraper = scraper;
        this.matchRepository = matchRepository;
        this.playerRepository = playerRepository;

        setTitle("TTL Elite Series Desktop UI");
        setSize(1200, 700);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));

        JButton editOddsBtn = new JButton("Edit Vegas Odds");
        editOddsBtn.addActionListener(e -> editVegasOdds());

        JButton searchPlayersBtn = new JButton("Search Players");
        searchPlayersBtn.addActionListener(e -> {
            Player player = pickPlayerSearchDialog("Search Players");
            if (player != null) {
                textArea.append("Selected player: " + player.getName() + "\n");
            }
        });

        JButton scrapeDataBtn = new JButton("Scrape Data");
        scrapeDataBtn.addActionListener(e -> scrapeNewData());

        JButton analyzeMatchupBtn = new JButton("Analyze Matchup");
        analyzeMatchupBtn.addActionListener(e -> analyzeMatchup());

        JButton viewSavedBtn = new JButton("View Saved Matchups");
        viewSavedBtn.addActionListener(e -> viewSavedMatchups());

        buttonPanel.add(editOddsBtn);
        buttonPanel.add(searchPlayersBtn);
        buttonPanel.add(scrapeDataBtn);
        buttonPanel.add(analyzeMatchupBtn);
        buttonPanel.add(viewSavedBtn);

        textArea = new JTextArea();
        textArea.setEditable(false);
        textArea.setFont(new Font("Monospaced", Font.PLAIN, 13));
        JScrollPane scrollPane = new JScrollPane(textArea);

        getContentPane().setLayout(new BorderLayout());
        getContentPane().add(buttonPanel, BorderLayout.NORTH);
        getContentPane().add(scrollPane, BorderLayout.CENTER);
    }

    private void editVegasOdds() {
        textArea.setText("Editing Vegas Odds...\n");
        if (parlayMatches.isEmpty()) {
            textArea.append("No matchups available to edit.\n");
            return;
        }
        JDialog dialog = new JDialog(this, "Edit Vegas Odds", true);
        dialog.setSize(600, 400);
        dialog.setLayout(new BorderLayout());
        VegasTableModel tableModel = new VegasTableModel(parlayMatches);
        JTable table = new JTable(tableModel);
        JScrollPane scrollPane = new JScrollPane(table);
        JButton saveButton = new JButton("Save");
        saveButton.addActionListener(e -> {
            tableModel.saveBack();
            dialog.dispose();
            textArea.append("Vegas odds updated.\n");
        });
        JPanel buttonPanel = new JPanel();
        buttonPanel.add(saveButton);
        dialog.add(scrollPane, BorderLayout.CENTER);
        dialog.add(buttonPanel, BorderLayout.SOUTH);
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }

    private Player pickPlayerSearchDialog(String title) {
        JDialog dialog = new JDialog(this, title, true);
        dialog.setSize(600, 400);
        dialog.setLayout(new BorderLayout());
        DefaultListModel<Player> listModel = new DefaultListModel<>();
        JTextField searchField = new JTextField(20);
        JList<Player> playerList = new JList<>(listModel);
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            private void filterPlayers() {
                String query = searchField.getText().trim();
                listModel.clear();
                if (query.isEmpty()) {
                    playerRepository.findAll().forEach(listModel::addElement);
                } else {
                    playerRepository.searchPlayers(query).forEach(listModel::addElement);
                }
            }
            @Override public void insertUpdate(DocumentEvent e) { filterPlayers(); }
            @Override public void removeUpdate(DocumentEvent e) { filterPlayers(); }
            @Override public void changedUpdate(DocumentEvent e) { filterPlayers(); }
        });
        playerRepository.findAll().forEach(listModel::addElement);
        playerList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JButton selectButton = new JButton("Select");
        selectButton.addActionListener(e -> dialog.dispose());
        JPanel topPanel = new JPanel(new FlowLayout());
        topPanel.add(new JLabel("Search:"));
        topPanel.add(searchField);
        JPanel bottomPanel = new JPanel(new FlowLayout());
        bottomPanel.add(selectButton);
        dialog.add(topPanel, BorderLayout.NORTH);
        dialog.add(new JScrollPane(playerList), BorderLayout.CENTER);
        dialog.add(bottomPanel, BorderLayout.SOUTH);
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
        return playerList.getSelectedValue();
    }

    private void scrapeNewData() {
        String startPageStr = JOptionPane.showInputDialog(this, "Enter start page:", "1");
        String endPageStr   = JOptionPane.showInputDialog(this, "Enter end page:", "1");
        if (startPageStr == null || endPageStr == null) {
            textArea.append("Scraping cancelled.\n");
            return;
        }
        try {
            int startPage = Integer.parseInt(startPageStr.trim());
            int endPage   = Integer.parseInt(endPageStr.trim());

            // These can throw IOException / InterruptedException
            List<String> matchLinks = scraper.scrapeMatchLinks(startPage, endPage);

            // This can throw IOException
            int savedCount = scraper.scrapeAndSaveMatchDetails(matchLinks);

            textArea.append("Scraped " + matchLinks.size() + " links, " + savedCount + " matches saved.\n");
        } catch (NumberFormatException ex) {
            textArea.append("Invalid page numbers entered.\n");
        } catch (java.io.IOException ex) {
            textArea.append("Network/parse error while scraping: " + ex.getMessage() + "\n");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            textArea.append("Scrape interrupted.\n");
        }
    }



    private void analyzeMatchup() {
        Player player1 = pickPlayerSearchDialog("Select Player 1 for Analysis");
        if (player1 == null) { textArea.append("Player 1 selection cancelled.\n"); return; }
        Player player2 = pickPlayerSearchDialog("Select Player 2 for Analysis");
        if (player2 == null) { textArea.append("Player 2 selection cancelled.\n"); return; }

        // Overall head-to-head from all matches
        double[] overallH2H = statisticsService.getHeadToHeadWinPercentage(player1.getName(), player2.getName());
        textArea.append("Overall Head-to-Head Analysis:\n");
        textArea.append(String.format("%s win percentage: %.2f%%\n", player1.getName(), overallH2H[0] * 100));
        textArea.append(String.format("%s win percentage: %.2f%%\n\n", player2.getName(), overallH2H[1] * 100));

        // Last 10 head-to-head statistics
        List<Match> recentH2H = statisticsService.getRecentMatchesBetweenPlayers(player1, player2, 10);
        double h2hP1 = recentH2H.isEmpty() ? 0.5 : statisticsService.computeWinPercentageForPlayer(recentH2H, player1);
        double avgMarginP1 = statisticsService.computeAverageMargin(recentH2H, player1);
        double avgMarginP2 = statisticsService.computeAverageMargin(recentH2H, player2);
        textArea.append("Last 10 Head-to-Head Matches:\n");
        textArea.append(String.format("%s win percentage: %.2f%% (Avg margin: %.2f)\n", player1.getName(), h2hP1 * 100, avgMarginP1));
        textArea.append(String.format("%s win percentage: %.2f%% (Avg margin: %.2f)\n\n", player2.getName(), (1 - h2hP1) * 100, avgMarginP2));

        // Individual recent form: last 50 matches
        List<Match> recent50P1 = statisticsService.getRecentMatchesForPlayer(player1, 50);
        double individualP1 = statisticsService.computeWinPercentageForPlayer(recent50P1, player1);
        List<Match> recent50P2 = statisticsService.getRecentMatchesForPlayer(player2, 50);
        double individualP2 = statisticsService.computeWinPercentageForPlayer(recent50P2, player2);
        textArea.append("Recent Form (Last 50 Matches):\n");
        textArea.append(String.format("%s win percentage: %.2f%% (%d matches)\n", player1.getName(), individualP1 * 100, recent50P1.size()));
        textArea.append(String.format("%s win percentage: %.2f%% (%d matches)\n\n", player2.getName(), individualP2 * 100, recent50P2.size()));

        // Performance Trend: compare last 10 overall vs last 50 overall
        List<Match> recent10P1 = statisticsService.getRecentMatchesForPlayer(player1, 10);
        double trend1 = 0.5 + (statisticsService.computeWinPercentageForPlayer(recent10P1, player1) - individualP1);
        List<Match> recent10P2 = statisticsService.getRecentMatchesForPlayer(player2, 10);
        double trend2 = 0.5 + (statisticsService.computeWinPercentageForPlayer(recent10P2, player2) - individualP2);
        textArea.append("Performance Trend:\n");
        textArea.append(String.format("%s trend factor: %.2f\n", player1.getName(), trend1));
        textArea.append(String.format("%s trend factor: %.2f\n\n", player2.getName(), trend2));

        // New Similar Opponent Performance: last 10 matches against opponents similar to the target
        double similarOpp1 = statisticsService.getSimilarOpponentWinPercentage(player1, player2, 10);
        double similarOpp2 = statisticsService.getSimilarOpponentWinPercentage(player2, player1, 10);
        textArea.append("Similar Opponent Performance:\n");
        textArea.append(String.format("%s win percentage against similar opponents: %.2f%%\n", player1.getName(), similarOpp1 * 100));
        textArea.append(String.format("%s win percentage against similar opponents: %.2f%%\n\n", player2.getName(), similarOpp2 * 100));

        // Advanced composite statistics (including the new factor)
        double[] composite = statisticsService.getAdvancedMatchupStatistics(player1, player2);
        textArea.append("Composite Matchup Probability (Advanced Analysis):\n");
        textArea.append(String.format("%s: %.2f%%\n", player1.getName(), composite[0] * 100));
        textArea.append(String.format("%s: %.2f%%\n\n", player2.getName(), composite[1] * 100));

        // Our calculated American odds
        int ourAmericanOddsP1 = statisticsService.computeAmericanOdds(composite[0]);
        int ourAmericanOddsP2 = statisticsService.computeAmericanOdds(composite[1]);
        textArea.append("Calculated American Odds (Our Analysis):\n");
        textArea.append(String.format("%s: %d\n", player1.getName(), ourAmericanOddsP1));
        textArea.append(String.format("%s: %d\n\n", player2.getName(), ourAmericanOddsP2));

        // Ask user for sportsbook odds
        String sportsbookOdds1Str = JOptionPane.showInputDialog(this, "Enter sportsbook American odds for " + player1.getName() + ":", "");
        String sportsbookOdds2Str = JOptionPane.showInputDialog(this, "Enter sportsbook American odds for " + player2.getName() + ":", "");
        int sportsbookOdds1, sportsbookOdds2;
        try {
            sportsbookOdds1 = Integer.parseInt(sportsbookOdds1Str.trim());
            sportsbookOdds2 = Integer.parseInt(sportsbookOdds2Str.trim());
        } catch(NumberFormatException e) {
            textArea.append("Invalid sportsbook odds input. Skipping sportsbook comparison.\n");
            sportsbookOdds1 = ourAmericanOddsP1;
            sportsbookOdds2 = ourAmericanOddsP2;
        }
        double impliedP1 = statisticsService.americanOddsToProbability(sportsbookOdds1);
        double impliedP2 = statisticsService.americanOddsToProbability(sportsbookOdds2);
        textArea.append("Sportsbook Implied Probabilities:\n");
        textArea.append(String.format("%s: %.2f%%\n", player1.getName(), impliedP1 * 100));
        textArea.append(String.format("%s: %.2f%%\n\n", player2.getName(), impliedP2 * 100));

        // Recommendation logic: if our composite probability exceeds sportsbook implied probability by more than 5%
        double threshold = 0.05;
        String recommendation;
        if ((composite[0] - impliedP1) > threshold && (composite[0] - impliedP1) > (composite[1] - impliedP2)) {
            recommendation = "Bet on " + player1.getName();
        } else if ((composite[1] - impliedP2) > threshold && (composite[1] - impliedP2) > (composite[0] - impliedP1)) {
            recommendation = "Bet on " + player2.getName();
        } else {
            recommendation = "Pass on this matchup";
        }
        textArea.append("Recommendation: " + recommendation + "\n\n");

        // Display detailed history of last 10 head-to-head matches
        JDialog historyDialog = new JDialog(this, "Last 10 Head-to-Head Matches", true);
        historyDialog.setSize(600, 400);
        MatchHistoryTableModel historyModel = new MatchHistoryTableModel(recentH2H);
        JTable historyTable = new JTable(historyModel);
        historyDialog.add(new JScrollPane(historyTable));
        historyDialog.setLocationRelativeTo(this);
        historyDialog.setVisible(true);

        // Prompt user to save this matchup analysis
        int saveOption = JOptionPane.showConfirmDialog(this, "Save this matchup for later review?", "Save Matchup", JOptionPane.YES_NO_OPTION);
        if (saveOption == JOptionPane.YES_OPTION) {
            SavedMatchup sm = new SavedMatchup(player1, player2, h2hP1, overallH2H[0], recentH2H, composite,
                    ourAmericanOddsP1, ourAmericanOddsP2, sportsbookOdds1, sportsbookOdds2, recommendation);
            savedMatchups.add(sm);
            textArea.append("Matchup saved: " + sm + "\n");
        }

        // Display head-to-head chart
        JPanel chartPanel = ChartUtils.createHeadToHeadBarChart(player1.getName(), overallH2H[0], player2.getName(), overallH2H[1]);
        JDialog chartDialog = new JDialog(this, "Head-to-Head Chart", true);
        chartDialog.setSize(500, 400);
        chartDialog.getContentPane().add(chartPanel);
        chartDialog.setLocationRelativeTo(this);
        chartDialog.setVisible(true);
    }

    private void viewSavedMatchups() {
        if (savedMatchups.isEmpty()) {
            textArea.append("No saved matchups available.\n");
            return;
        }
        JDialog dialog = new JDialog(this, "Saved Matchups", true);
        dialog.setSize(400, 300);
        DefaultListModel<SavedMatchup> listModel = new DefaultListModel<>();
        savedMatchups.forEach(listModel::addElement);
        JList<SavedMatchup> matchupList = new JList<>(listModel);
        matchupList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JButton viewButton = new JButton("View Details");
        viewButton.addActionListener(e -> {
            SavedMatchup selected = matchupList.getSelectedValue();
            if (selected != null) {
                showSavedMatchupDetails(selected);
            }
        });
        dialog.setLayout(new BorderLayout());
        dialog.add(new JScrollPane(matchupList), BorderLayout.CENTER);
        JPanel panel = new JPanel();
        panel.add(viewButton);
        dialog.add(panel, BorderLayout.SOUTH);
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }

    private void showSavedMatchupDetails(SavedMatchup matchup) {
        StringBuilder sb = new StringBuilder();
        sb.append("Matchup: ").append(matchup.player1.getName())
                .append(" vs ").append(matchup.player2.getName()).append("\n");
        sb.append(String.format("Head-to-Head (Last 10) %s: %.2f%%, %s: %.2f%%\n",
                matchup.player1.getName(), matchup.h2hWinPct * 100,
                matchup.player2.getName(), (1 - matchup.h2hWinPct) * 100));
        sb.append(String.format("Composite Probabilities: %s: %.2f%%, %s: %.2f%%\n",
                matchup.player1.getName(), matchup.compositeProbabilities[0] * 100,
                matchup.player2.getName(), matchup.compositeProbabilities[1] * 100));
        sb.append(String.format("Our American Odds: %s: %d, %s: %d\n",
                matchup.player1.getName(), matchup.ourAmericanOddsP1,
                matchup.player2.getName(), matchup.ourAmericanOddsP2));
        sb.append(String.format("Sportsbook Odds: %s: %d, %s: %d\n",
                matchup.player1.getName(), matchup.sportsbookOddsP1,
                matchup.player2.getName(), matchup.sportsbookOddsP2));
        sb.append("Recommendation: " + matchup.recommendation + "\n");
        JDialog detailDialog = new JDialog(this, "Saved Matchup Details", true);
        detailDialog.setSize(600, 400);
        JTextArea detailArea = new JTextArea(sb.toString());
        detailArea.setEditable(false);
        detailArea.setFont(new Font("Monospaced", Font.PLAIN, 13));
        JPanel historyPanel = new JPanel(new BorderLayout());
        historyPanel.add(new JScrollPane(detailArea), BorderLayout.NORTH);
        MatchHistoryTableModel historyModel = new MatchHistoryTableModel(matchup.last10Matches);
        JTable historyTable = new JTable(historyModel);
        historyPanel.add(new JScrollPane(historyTable), BorderLayout.CENTER);
        detailDialog.getContentPane().add(historyPanel);
        detailDialog.setLocationRelativeTo(this);
        detailDialog.setVisible(true);
    }

    // Table model for match history details
    private static class MatchHistoryTableModel extends AbstractTableModel {
        private final List<Match> data;
        private final String[] columns = {"Date", "Winner", "Score"};
        public MatchHistoryTableModel(List<Match> data) { this.data = data; }
        @Override public int getRowCount() { return data.size(); }
        @Override public int getColumnCount() { return columns.length; }
        @Override public String getColumnName(int column) { return columns[column]; }
        @Override public Object getValueAt(int rowIndex, int columnIndex) {
            Match match = data.get(rowIndex);
            switch (columnIndex) {
                case 0: return match.getDate();
                case 1:
                    try {
                        int left = Integer.parseInt(match.getResult().split("[:/-]")[0].trim());
                        int right = Integer.parseInt(match.getResult().split("[:/-]")[1].trim());
                        return left > right ? match.getPlayer1().getName() : match.getPlayer2().getName();
                    } catch (Exception e) { return "N/A"; }
                case 2: return match.getResult();
                default: return "";
            }
        }
    }

    // Table model for editing Vegas odds
    private static class VegasTableModel extends AbstractTableModel {
        private final List<ParlayMatch> data;
        private final String[] columns = {"#", "Player 1", "Player 2", "Vegas Odds P1", "Vegas Odds P2"};
        public VegasTableModel(List<ParlayMatch> data) { this.data = data; }
        @Override public int getRowCount() { return data.size(); }
        @Override public int getColumnCount() { return columns.length; }
        @Override public String getColumnName(int column) { return columns[column]; }
        @Override public Object getValueAt(int rowIndex, int columnIndex) {
            ParlayMatch match = data.get(rowIndex);
            switch (columnIndex) {
                case 0: return rowIndex + 1;
                case 1: return match.getMatch().getPlayer1().getName();
                case 2: return match.getMatch().getPlayer2().getName();
                case 3: return match.getVegasOddsPlayer1();
                case 4: return match.getVegasOddsPlayer2();
                default: return "";
            }
        }
        @Override public boolean isCellEditable(int rowIndex, int columnIndex) { return columnIndex >= 3; }
        @Override public void setValueAt(Object aValue, int rowIndex, int columnIndex) {
            ParlayMatch match = data.get(rowIndex);
            try {
                int odds = Integer.parseInt(aValue.toString().trim());
                if (columnIndex == 3) match.setVegasOddsPlayer1(odds);
                else if (columnIndex == 4) match.setVegasOddsPlayer2(odds);
            } catch (NumberFormatException e) { }
        }
        public void saveBack() {
            // Implement persistence if needed
        }
    }

    // Inner class to hold saved matchup analysis with advanced stats
    private static class SavedMatchup {
        private final Player player1;
        private final Player player2;
        private final double h2hWinPct; // last 10 head-to-head win % for player1
        private final double overallH2hPct; // overall head-to-head win % for player1
        private final List<Match> last10Matches;
        private final double[] compositeProbabilities;
        private final int ourAmericanOddsP1;
        private final int ourAmericanOddsP2;
        private final int sportsbookOddsP1;
        private final int sportsbookOddsP2;
        private final String recommendation;
        public SavedMatchup(Player player1, Player player2, double h2hWinPct, double overallH2hPct, List<Match> last10Matches,
                            double[] compositeProbabilities, int ourAmericanOddsP1, int ourAmericanOddsP2,
                            int sportsbookOddsP1, int sportsbookOddsP2, String recommendation) {
            this.player1 = player1;
            this.player2 = player2;
            this.h2hWinPct = h2hWinPct;
            this.overallH2hPct = overallH2hPct;
            this.last10Matches = last10Matches;
            this.compositeProbabilities = compositeProbabilities;
            this.ourAmericanOddsP1 = ourAmericanOddsP1;
            this.ourAmericanOddsP2 = ourAmericanOddsP2;
            this.sportsbookOddsP1 = sportsbookOddsP1;
            this.sportsbookOddsP2 = sportsbookOddsP2;
            this.recommendation = recommendation;
        }
        @Override
        public String toString() {
            return player1.getName() + " vs " + player2.getName() + " | Rec: " + recommendation;
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            PlayerService playerService = null; // Replace with actual service
            StatisticsService statisticsService = null; // Replace with actual service
            TtSeriesScraper scraper = null; // Replace with actual scraper
            MatchRepository matchRepository = null; // Replace with actual repository
            PlayerRepository playerRepository = null; // Replace with actual repository
            new DesktopUI(playerService, statisticsService, scraper, matchRepository, playerRepository).setVisible(true);
        });
    }
}